package com.emergentterrain.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * LLM-written narrative sections that replace the template text in a published paper.
 * Null/blank fields fall back to the template values stored in the PaperDraft.
 * followUp, when non-null, is stored on the author bot and used as search params for
 * their next paper, creating a chain of inquiry from one result to the next.
 * aiModel identifies which LLM generated the narration; null when template fallback is used.
 * confidenceScore (0.0–1.0) is the LLM's self-assessed confidence in the findings quality.
 * citedPaperIds is the structured list of prior paper IDs cited in the narrative.
 * callPath is the LLM call audit trail for this paper.
 *
 * Phase 2b — typedCitations carries optional per-citation relationship info
 * (id + RelationshipType + confidence). When the LLM emits the richer
 * {@code cited_papers} JSON shape, the parser populates this list and the
 * publish path writes typed PaperCitation rows instead of the default
 * {@link RelationshipType#EXTENDS}. Empty list = caller should fall back to
 * the untyped {@link #citedPaperIds} (heuristic EXTENDS edges).
 *
 * Phase 4c Piece 2 gate 3 — {@code templateId} and {@code templateVersion}
 * identify which prompt template variant produced this narration. Stamped at
 * pipeline emission so {@link UniversityService#publishPaper} can copy the
 * provenance onto the persisted {@link ResearchPaper}. Both null when the
 * narration came from a path that has not yet been instrumented (e.g. fallback
 * suppression results); the publish path treats null as "unattributed."
 *
 * Phase 7 F2 — {@code findingsMap} is the canonical, per-computation form of the
 * findings prose, keyed by 1-based computation index. The legacy String-shaped
 * {@link #findings()} accessor remains (joins the map's values in key order, or
 * returns the single value when the map has one entry — bit-identical to the
 * pre-F2 String field). All legacy constructors that took a {@code String findings}
 * still compile: they wrap the prose as {@code Map.of(1, prose)} into the canonical
 * record. The Map shape is what F2's per-block truncation guard and per-block H3
 * markdown renderer consume.
 */
public record NarrationResult(String hypothesis, Map<Integer, String> findingsMap, String conclusion,
                               FollowUpParams followUp, String aiModel,
                               double confidenceScore, List<String> citedPaperIds,
                               String callPath, List<TypedCitation> typedCitations,
                               List<ExtractedFinding> formalFindings,
                               String templateId, String templateVersion,
                               /* Phase 6 D2 — Discussion and Related-Work sections.
                                  Both nullable; null fields render no section. */
                               String discussion, String relatedWork,
                               /* Phase 7 G8 — audit-4b dimension names that fired on this paper.
                                  Empty list = audit clean / didn't run. Null treated as empty. */
                               List<String> auditGapDimensions,
                               /* Phase 7 F5.3 — agentic tool-call trace from the professor stage.
                                  Tier 2 of F5's provenance scheme. Empty list = professor stage
                                  used the JSON-path (no tool calls) or didn't run. Null treated
                                  as empty. Stamped onto the result via
                                  {@link #withToolCallTrace(List)} at the call site that ran the
                                  tool loop. */
                               List<com.emergentterrain.llm.ToolInvocation> toolCallTrace,
                               /* Phase 7 F7.2 — multi-reviewer panel outcome. Null when the panel
                                  did not run (flag off, no reviewer pools resolved, or paper had
                                  no tool-call trace to grade). Carries the median grades,
                                  agreement score, consensus cited/contradiction lists, and the
                                  raw N reviews. Stamped via {@link #withPanelOutcome(PanelOutcome)}. */
                               PanelOutcome panelOutcome) {

    /** Phase 2b — one typed citation entry from the LLM's structured output. */
    public record TypedCitation(String paperId, RelationshipType type, double confidence) {}

    /**
     * Phase 4 Iter 9 — a single structured finding the narration LLM extracted
     * from the paper's results. Persisted to {@link FormalFinding} when the
     * findings flag is on. Carried on NarrationResult rather than written
     * directly so the publish path keeps the persistence + provenance logic
     * (extractedByBotId, validation, error handling) in one place.
     */
    public record ExtractedFinding(
            RelationType relationType,
            String dependentVariable,
            List<String> independentVariables,
            String equationForm,
            MathPhysicsEngine.ClaimStatus claimStatus,
            double confidence,
            double validRegionMin,
            double validRegionMax) {}

    /**
     * Phase 7 F2 — joined-prose form of the findings map. Single-entry map
     * returns its sole value verbatim (legacy behaviour). Multi-entry map
     * returns values joined by a blank line, in ascending key order. Null
     * or empty map returns null so downstream null-checks behave identically
     * to the pre-F2 String field.
     */
    public String findings() {
        if (findingsMap == null || findingsMap.isEmpty()) return null;
        if (findingsMap.size() == 1) {
            return findingsMap.values().iterator().next();
        }
        return new TreeMap<>(findingsMap).values().stream()
                .filter(v -> v != null)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Phase 7 F2 — single-prose legacy String-shaped 15-arg constructor. Wraps
     * the prose as a single-entry map at key 1 so the canonical {@link #findingsMap()}
     * field stays consistent. Null prose stays null.
     */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork,
                           List<String> auditGapDimensions) {
        this(hypothesis, wrapSingle(findings), conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations,
                formalFindings, templateId, templateVersion, discussion, relatedWork,
                auditGapDimensions, null, null);
    }

    /** 14-arg backward-compatible — null auditGapDimensions. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, null);
    }

    /** 12-arg backward-compatible — null Discussion / Related-Work fields. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, null, null, null);
    }

    /** 10-arg backward-compatible — null template provenance + null Discussion/Related-Work. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings, null, null, null, null, null);
    }

    /** 9-arg backward-compatible constructor — empty formalFindings, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, List.of(), null, null, null, null, null);
    }

    /** 8-arg backward-compatible — empty typed citations, empty findings, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, List.of(), List.of(), null, null, null, null, null);
    }

    /** 7-arg backward-compatible — null callPath, empty extras, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, null, List.of(), List.of(), null, null, null, null, null);
    }

    /** Return a copy of this result with template provenance stamped on. */
    public NarrationResult withTemplateProvenance(String templateId, String templateVersion) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome);
    }

    /** Phase 6 D2 — return a copy with the Discussion + Related-Work fields stamped on. */
    public NarrationResult withDiscussionAndRelatedWork(String discussion, String relatedWork) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome);
    }

    /** Phase 7 G8 — return a copy with audit-4b gap dimensions stamped on. */
    public NarrationResult withAuditGapDimensions(List<String> auditGapDimensions) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome);
    }

    /** Phase 7 F5.3 — return a copy with the agentic tool-call trace stamped on. */
    public NarrationResult withToolCallTrace(List<com.emergentterrain.llm.ToolInvocation> trace) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                trace, panelOutcome);
    }

    /** Phase 7 F7.2 — return a copy with the panel outcome stamped on. */
    public NarrationResult withPanelOutcome(PanelOutcome outcome) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, outcome);
    }

    /**
     * Phase 7 F2 — wrap a single prose string into the canonical {1: prose} single-entry
     * map. Null prose returns null (downstream null-checks behave identically to the
     * pre-F2 String field).
     */
    private static Map<Integer, String> wrapSingle(String findings) {
        if (findings == null) return null;
        return Map.of(1, findings);
    }

    /**
     * Phase 7 F2 — construct an ordered findings map from a {@code computation_N} JSON
     * shape parsed at the LLM-output boundary. Sorts keys ascending so downstream
     * consumers (truncation guard, markdown renderer, validators) see a stable order
     * regardless of how the LLM serialised the object. Returns null when the input
     * is null or empty (preserving the legacy null-findings contract).
     */
    public static Map<Integer, String> orderedFindingsMap(Map<Integer, String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        Map<Integer, String> ordered = new LinkedHashMap<>();
        new TreeMap<>(raw).forEach((k, v) -> {
            if (v != null && !v.isBlank()) ordered.put(k, v);
        });
        return ordered.isEmpty() ? null : ordered;
    }
}
