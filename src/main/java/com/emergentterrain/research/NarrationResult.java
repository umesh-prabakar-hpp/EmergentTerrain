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
 * callPath is the LLM call audit trail for this paper (e.g. "audit:4b:2gaps → prof:llama-70b:ok").
 */
public record NarrationResult(String hypothesis, String findings, String conclusion,
                               FollowUpParams followUp, String aiModel,
                               double confidenceScore, List<String> citedPaperIds,
                               String callPath) {

    /** Backward-compatible constructor without callPath (null path). */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore, citedPaperIds, null);
    }
}
