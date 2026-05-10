package com.emergentterrain.research;

import java.util.List;

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
 */
public record NarrationResult(String hypothesis, String findings, String conclusion,
                               FollowUpParams followUp, String aiModel,
                               double confidenceScore, List<String> citedPaperIds,
                               String callPath, List<TypedCitation> typedCitations,
                               List<ExtractedFinding> formalFindings) {

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

    /** 9-arg backward-compatible constructor — empty formalFindings. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, List.of());
    }

    /** 8-arg backward-compatible — empty typed citations, empty findings. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, List.of(), List.of());
    }

    /** 7-arg backward-compatible — null callPath, empty extras. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, null, List.of(), List.of());
    }
}
