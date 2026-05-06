package com.emergentterrain.research;

/**
 * Deterministic classification of a computation result, assigned before any LLM narration call.
 * Prevents post-hoc rationalization by fixing the verdict from numeric evidence alone.
 *
 * DEGENERATE   — zero/non-finite values or authorScore ≤ 0; paper is rejected silently.
 * NULL_RESULT  — computation ran but the pre-registered hypothesis is not supported.
 * PARTIALLY_SUPPORTED — theory observational or weak claim with reasonable score.
 * SUPPORTED    — theory check passed and claim is supported.
 */
public enum ResultClassification {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    NULL_RESULT,
    DEGENERATE
}
