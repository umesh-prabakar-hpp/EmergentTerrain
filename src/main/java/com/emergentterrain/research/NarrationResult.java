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
 *
 * Phase 4c Piece 2 gate 3 — {@code templateId} and {@code templateVersion}
 * identify which prompt template variant produced this narration. Stamped at
 * pipeline emission so {@link UniversityService#publishPaper} can copy the
 * provenance onto the persisted {@link ResearchPaper}. Both null when the
 * narration came from a path that has not yet been instrumented (e.g. fallback
 * suppression results); the publish path treats null as "unattributed."
 */
public record NarrationResult(String hypothesis, String findings, String conclusion,
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
                               List<String> auditGapDimensions) {

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
        return new NarrationResult(hypothesis, findings, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions);
    }

    /** Phase 6 D2 — return a copy with the Discussion + Related-Work fields stamped on. */
    public NarrationResult withDiscussionAndRelatedWork(String discussion, String relatedWork) {
        return new NarrationResult(hypothesis, findings, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions);
    }

    /** Phase 7 G8 — return a copy with audit-4b gap dimensions stamped on. */
    public NarrationResult withAuditGapDimensions(List<String> auditGapDimensions) {
        return new NarrationResult(hypothesis, findings, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions);
    }
}
