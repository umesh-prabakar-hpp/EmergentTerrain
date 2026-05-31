package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 3 — how far a branch's result can be
 * trusted. The loop must never launder one level into another
 * (frontier-escalation-loop.md §5): a frontier paper carries the value's trust
 * verbatim.
 * <ul>
 *   <li>{@code FULL} — moat-validated value (B1/B2).</li>
 *   <li>{@code VALIDATED_PROXY} — validated, but answers a *nearby* question (B3).</li>
 *   <li>{@code MODEL_FIT} — an extrapolation under a fitted model (B4); carries fit quality.</li>
 *   <li>{@code LITERATURE_ONLY} — not verifiable here; cite, never assert as derived (B5).</li>
 * </ul>
 */
public enum TrustLevel {
    FULL,
    VALIDATED_PROXY,
    MODEL_FIT,
    LITERATURE_ONLY
}
