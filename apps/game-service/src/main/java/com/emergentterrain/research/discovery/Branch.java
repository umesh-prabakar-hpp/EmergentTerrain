package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 3 — the method branches the loop can take,
 * ordered (by declaration / ordinal) from most to least trustworthy *here*
 * (frontier-escalation-loop.md §4). The pivot prefers earlier branches.
 * <ul>
 *   <li>{@code DIRECT_EVALUATION} (B1) — exact value from the sieve.</li>
 *   <li>{@code ALGORITHMIC_ACCELERATION} (B2) — a cleverer validated method (discovery).</li>
 *   <li>{@code PROXY_REFORMULATION} (B3) — a tractable nearby quantity, moat-validated.</li>
 *   <li>{@code PREDICTIVE_MODEL} (B4) — a model checked on the overlap, then extrapolated.</li>
 *   <li>{@code ANALYTIC} (B5) — non-constructive; unverifiable here (the honest boundary).</li>
 * </ul>
 */
public enum Branch {
    DIRECT_EVALUATION,
    ALGORITHMIC_ACCELERATION,
    PROXY_REFORMULATION,
    PREDICTIVE_MODEL,
    ANALYTIC
}
