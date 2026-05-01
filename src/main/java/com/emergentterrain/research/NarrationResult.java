package com.emergentterrain.research;

/**
 * LLM-written narrative sections that replace the template text in a published paper.
 * Null/blank fields fall back to the template values stored in the PaperDraft.
 * followUp, when non-null, is stored on the author bot and used as search params for
 * their next paper, creating a chain of inquiry from one result to the next.
 */
public record NarrationResult(String hypothesis, String findings, String conclusion,
                               FollowUpParams followUp) {}
