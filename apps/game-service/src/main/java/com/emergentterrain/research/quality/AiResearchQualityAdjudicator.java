package com.emergentterrain.research.quality;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.service.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Default-off Phase 43 AI adjudicator for newly persisted outputs.
 *
 * <p>Its first evidence bundle has no independent-replication or benchmark artifact, so it may
 * only assign {@link ResearchQualityGrade#RESEARCH_NOTE} or
 * {@link ResearchQualityGrade#RESEARCH_DRAFT}. The parser fails closed on a stronger grade rather
 * than allowing a model to assert human quality from computation or fluent narration alone.
 */
@Service
@ConditionalOnProperty(name = "app.research.quality-adjudication.enabled", havingValue = "true")
public class AiResearchQualityAdjudicator {

    private static final Logger log = LoggerFactory.getLogger(AiResearchQualityAdjudicator.class);

    private final LlmRouter router;
    private final ObjectMapper objectMapper;
    private final ResearchQualityAssessmentService assessmentService;
    private final String rubricVersion;
    private final int maxEvidenceChars;

    public AiResearchQualityAdjudicator(
            LlmRouter router,
            ObjectMapper objectMapper,
            ResearchQualityAssessmentService assessmentService,
            @Value("${app.research.quality-adjudication.rubric-version:phase-43-rubric-v1}")
            String rubricVersion,
            @Value("${app.research.quality-adjudication.max-evidence-chars:12000}")
            int maxEvidenceChars) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.assessmentService = assessmentService;
        this.rubricVersion = rubricVersion;
        this.maxEvidenceChars = Math.max(2_000, maxEvidenceChars);
    }

    /** Best-effort post-persistence assessment; a failed adjudication never rolls back a paper. */
    public void adjudicate(ResearchPaper paper, PaperDraft draft) {
        if (paper == null || paper.paperId() == null || paper.paperId().isBlank()) {
            return;
        }
        Set<String> allowedEvidenceReferences = allowedEvidenceReferences(paper, draft);
        try {
            String response = router.callPrimary(prompt(paper, draft, allowedEvidenceReferences));
            ParseOutcome outcome = parseOutcome(objectMapper, response, paper.paperId(), rubricVersion,
                    router.primaryModel(), allowedEvidenceReferences);
            if (outcome.decision().isEmpty()) {
                log.warn("Phase 43 quality adjudication rejected response paperId={} reason={}",
                        paper.paperId(), outcome.rejectionReason());
                return;
            }
            ResearchPaperQualityAssessment stored = assessmentService.recordAiAssessment(outcome.decision().get());
            log.info("Phase 43 AI quality assessment persisted paperId={} revision={} grade={}",
                    paper.paperId(), stored.assessmentRevision(), stored.grade());
        } catch (RuntimeException e) {
            log.warn("Phase 43 AI quality adjudication failed paperId={} (paper remains persisted): {}",
                    paper.paperId(), e.getMessage());
        }
    }

    /** Same bounded AI judgement for a historical persisted output, which has no live draft. */
    public void adjudicateHistorical(ResearchPaper paper) {
        adjudicate(paper, null);
    }

    static Optional<AiResearchQualityAssessment> parseResponse(
            ObjectMapper mapper,
            String response,
            String paperId,
            String rubricVersion,
            String assessorModel,
            Set<String> allowedEvidenceReferences) {
        return parseOutcome(mapper, response, paperId, rubricVersion, assessorModel,
                allowedEvidenceReferences).decision();
    }

    private static ParseOutcome parseOutcome(
            ObjectMapper mapper,
            String response,
            String paperId,
            String rubricVersion,
            String assessorModel,
            Set<String> allowedEvidenceReferences) {
        try {
            String json = extractJsonObject(response);
            if (json == null) return ParseOutcome.rejected("no_json_object");
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return ParseOutcome.rejected("json_not_object");
            ResearchQualityGrade grade;
            try {
                grade = ResearchQualityGrade.valueOf(text(root, "grade"));
            } catch (IllegalArgumentException invalidGrade) {
                return ParseOutcome.rejected("invalid_grade");
            }
            // v1 cannot honestly infer either settled grade because independent replication and a
            // benchmark record are not yet in its bounded evidence bundle.
            if (grade != ResearchQualityGrade.RESEARCH_NOTE && grade != ResearchQualityGrade.RESEARCH_DRAFT) {
                return ParseOutcome.rejected("unsupported_grade");
            }
            // The AI judges this bounded bundle, but the service records its exact sources. Models
            // never supply identifiers, so they cannot smuggle in an external paper or replication.
            List<String> evidence = List.copyOf(allowedEvidenceReferences);
            return ParseOutcome.accepted(new AiResearchQualityAssessment(
                    paperId, rubricVersion, assessorModel, grade, text(root, "rationale"), evidence,
                    strings(root.path("unmet_requirements")), strings(root.path("contradictions")),
                    optionalText(root, "reviewer_summary"), "", ""));
        } catch (Exception e) {
            return ParseOutcome.rejected("invalid_response_schema");
        }
    }

    private String prompt(ResearchPaper paper, PaperDraft draft, Set<String> evidenceReferences) {
        String evidence = "PAPER ID: " + paper.paperId() + "\n"
                + "WORKFLOW STATUS: " + safe(paper.status()) + "\n"
                + "TITLE: " + safe(paper.title()) + "\n"
                + "HYPOTHESIS: " + safe(paper.hypothesis()) + "\n"
                + "METHOD: " + safe(paper.methodology()) + "\n"
                + "FINDINGS: " + safe(paper.findings()) + "\n"
                + "CONCLUSION: " + safe(paper.conclusion()) + "\n"
                + "LOCKED PROTOCOL: " + safe(draft == null || draft.preRegistration() == null
                        ? paper.protocolFingerprint() : draft.preRegistration().mappingNotes()) + "\n"
                + "PROTOCOL REUSE PROVENANCE: " + protocolReuseProvenance(paper, draft) + "\n"
                + "COMPUTATION TRACE: " + safe(paper.toolCallTrace()) + "\n"
                + "RELATED WORK: " + safe(paper.relatedWork()) + "\n"
                + "REVIEW PANEL: " + safe(paper.panelOutcome()) + "\n"
                + "SERVER-BOUND EVIDENCE REFERENCES: " + jsonArray(evidenceReferences);
        return """
                You are the independent AI quality adjudicator for a research record. Assess only
                the supplied evidence. Do not treat successful computation, peer score, workflow
                publication, or polished prose as proof of human-quality research. Preserve null
                findings. Do not invent references, replication, reviewers, or benchmark results.

                This v1 bundle has NO independent replication artifact and NO benchmark artifact.
                You may return only RESEARCH_NOTE or RESEARCH_DRAFT. Return one JSON object only:
                {"grade":"RESEARCH_NOTE|RESEARCH_DRAFT","rationale":"...",
                 "unmet_requirements":["..."],"contradictions":["..."],
                 "reviewer_summary":"..."}

                The server binds the displayed evidence references to the assessment record. Do not
                name, cite, or rely on any source outside the displayed evidence bundle.

                Evidence follows:
                """ + truncate(evidence);
    }

    private Set<String> allowedEvidenceReferences(ResearchPaper paper, PaperDraft draft) {
        Set<String> refs = new LinkedHashSet<>();
        refs.add("paper:" + paper.paperId());
        if ((draft != null && draft.preRegistration() != null)
                || !safe(paper.protocolFingerprint()).isBlank()) {
            refs.add("protocol:" + paper.paperId());
        }
        if ((draft != null && draft.preRegistration() != null
                && draft.preRegistration().protocolReuseProvenance() != null)
                || !safe(paper.protocolReuseProvenance()).isBlank()) {
            refs.add("protocol-reuse:" + paper.paperId());
        }
        if (!safe(paper.findings()).isBlank()) refs.add("computation:" + paper.paperId());
        if (!safe(paper.conclusion()).isBlank()) refs.add("narration:" + paper.paperId());
        if (!safe(paper.panelOutcome()).isBlank()) refs.add("review-panel:" + paper.paperId());
        return Set.copyOf(refs);
    }

    private String protocolReuseProvenance(ResearchPaper paper, PaperDraft draft) {
        if (draft != null && draft.preRegistration() != null
                && draft.preRegistration().protocolReuseProvenance() != null) {
            try {
                return objectMapper.writeValueAsString(draft.preRegistration().protocolReuseProvenance());
            } catch (Exception impossible) {
                return "";
            }
        }
        return safe(paper.protocolReuseProvenance());
    }

    private String jsonArray(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception impossible) {
            throw new IllegalStateException("could not encode allowed quality evidence references", impossible);
        }
    }

    private String truncate(String value) {
        return value.length() <= maxEvidenceChars ? value : value.substring(0, maxEvidenceChars);
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray() || node.size() > 128) return List.of();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode child : node) {
            if (!child.isTextual() || child.asText().isBlank()) return List.of();
            values.add(child.asText().strip());
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value.asText().strip();
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : "";
    }

    /** Accept a single JSON object even when a provider surrounds it with markdown fencing. */
    private static String extractJsonObject(String response) {
        if (response == null) return null;
        int open = response.indexOf('{');
        if (open < 0) return null;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = open; index < response.length(); index++) {
            char current = response.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return response.substring(open, index + 1);
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ParseOutcome(Optional<AiResearchQualityAssessment> decision, String rejectionReason) {
        static ParseOutcome accepted(AiResearchQualityAssessment decision) {
            return new ParseOutcome(Optional.of(decision), "");
        }

        static ParseOutcome rejected(String reason) {
            return new ParseOutcome(Optional.empty(), reason);
        }
    }
}
