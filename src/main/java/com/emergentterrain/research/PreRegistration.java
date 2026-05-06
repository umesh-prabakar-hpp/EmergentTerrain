package com.emergentterrain.research;

/**
 * Hypothesis pre-registration: written by the formulation LLM before computation runs.
 * Locking these fields before results are available eliminates post-hoc rationalization —
 * the narration LLM must assess whether its own prior prediction was met.
 *
 * mappingNotes captures any cross-domain constants or analogies used (e.g. L = depth × alpha),
 * forcing the author to justify the transformation before seeing whether it produces useful output.
 * Empty string for same-domain papers.
 */
public record PreRegistration(
        String hypothesis,
        String prediction,
        String falsificationCondition,
        String mappingNotes
) {}
