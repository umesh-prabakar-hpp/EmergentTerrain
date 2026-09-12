package com.emergentterrain.research.quality;

import com.emergentterrain.research.ProtocolReuseProvenance;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.service.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Separate fail-closed AI judgement for a reused study with retained reviewer-remediation requests.
 * It never changes workflow status, quality grade, protocol settlement, or publication.
 */
@Service
@ConditionalOnProperty(name = "app.research.quality-adjudication.enabled", havingValue = "true")
public class AiProtocolReuseRemediationAdjudicator {
    private final LlmRouter router;
    private final ObjectMapper mapper;
    private final ResearchPaperRepository papers;
    private final ProtocolReuseRemediationAssessmentService assessments;

    public AiProtocolReuseRemediationAdjudicator(LlmRouter router, ObjectMapper mapper,
            ResearchPaperRepository papers, ProtocolReuseRemediationAssessmentService assessments) {
        this.router = router; this.mapper = mapper; this.papers = papers; this.assessments = assessments;
    }

    public void adjudicate(ResearchPaper paper) {
        ProtocolReuseProvenance reuse = reuse(paper);
        if (reuse == null || reuse.reason() != ProtocolReuseProvenance.Reason.REVIEWER_REMEDIATION_REQUESTED
                || reuse.requestedRemediations().isEmpty()) return;
        ResearchPaper source = papers.findById(reuse.sourcePaperId()).orElse(null);
        if (source == null) return;
        Set<String> refs = evidenceReferences(paper, source);
        try {
            Decision decision = parse(mapper, router.callPrimary(prompt(paper, source, reuse)), refs).orElse(null);
            if (decision != null) {
                assessments.record(paper.paperId(), source.paperId(), router.primaryModel(), decision.outcome(),
                        decision.rationale(), List.copyOf(refs));
            }
        } catch (RuntimeException ignored) {
            // Best effort only: paper publication and its primary quality assessment remain intact.
        }
    }

    static Optional<Decision> parse(ObjectMapper mapper, String response, Set<String> refs) {
        try {
            int start = response == null ? -1 : response.indexOf('{');
            int end = response == null ? -1 : response.lastIndexOf('}');
            if (start < 0 || end < start) return Optional.empty();
            JsonNode json = mapper.readTree(response.substring(start, end + 1));
            ProtocolReuseRemediationOutcome outcome = ProtocolReuseRemediationOutcome.valueOf(
                    json.path("outcome").asText());
            String rationale = json.path("rationale").asText().strip();
            return rationale.isBlank() || refs == null || refs.isEmpty()
                    ? Optional.empty() : Optional.of(new Decision(outcome, rationale));
        } catch (Exception ignored) { return Optional.empty(); }
    }

    private ProtocolReuseProvenance reuse(ResearchPaper paper) {
        if (paper == null || paper.protocolReuseProvenance() == null) return null;
        try { return mapper.readValue(paper.protocolReuseProvenance(), ProtocolReuseProvenance.class); }
        catch (Exception ignored) { return null; }
    }

    private Set<String> evidenceReferences(ResearchPaper paper, ResearchPaper source) {
        Set<String> refs = new LinkedHashSet<>();
        refs.add("paper:" + paper.paperId()); refs.add("paper:" + source.paperId());
        refs.add("protocol-reuse:" + paper.paperId());
        if (source.panelOutcome() != null && !source.panelOutcome().isBlank()) refs.add("review-panel:" + source.paperId());
        if (paper.findings() != null && !paper.findings().isBlank()) refs.add("computation:" + paper.paperId());
        if (paper.conclusion() != null && !paper.conclusion().isBlank()) refs.add("narration:" + paper.paperId());
        if (paper.panelOutcome() != null && !paper.panelOutcome().isBlank()) refs.add("review-panel:" + paper.paperId());
        return Set.copyOf(refs);
    }

    private String prompt(ResearchPaper paper, ResearchPaper source, ProtocolReuseProvenance reuse) {
        return """
                You are an independent remediation assessor. Decide only whether the later paper's
                retained evidence addresses the listed source-panel requests. Do not infer success
                from fluent prose, status, score, or a claimed intention. This cannot assign a
                research quality grade. Return JSON only:
                {"outcome":"ADDRESSED|PARTIALLY_ADDRESSED|UNADDRESSED|INCONCLUSIVE","rationale":"..."}

                SOURCE REQUESTS: %s
                SOURCE FINDINGS: %s
                LATER FINDINGS: %s
                LATER CONCLUSION: %s
                LATER REVIEW: %s
                """.formatted(reuse.requestedRemediations(), safe(source.findings()), safe(paper.findings()),
                safe(paper.conclusion()), safe(paper.panelOutcome()));
    }
    private static String safe(String value) { return value == null ? "" : value.length() <= 5000 ? value : value.substring(0, 5000); }
    record Decision(ProtocolReuseRemediationOutcome outcome, String rationale) { }
}
