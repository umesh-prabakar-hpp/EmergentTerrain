package com.emergentterrain.research;

/**
 * Phase 30 fork 2b Path B — a beyond-sieve discovery domain a conjecture's scalars
 * can map to. Promoted from a {@code ResearchCycleService} nested enum (2026-06-05)
 * so it can be threaded through {@link PaperDraft} to the narration pipeline: the
 * professor stage needs to know a draft is frontier-framed to render its
 * {@code frontierHint} (the seed findings' steering text is consumed by the
 * tool-less first-pass narrator and reaches the tool-bearing professor only as
 * narrated prose — see {@code buildFrontierHint} in {@code ResearchNarrationService}).
 */
public enum FrontierDomain {
    MERTENS("M(x) = Σ_{n≤x} μ(n) (the cumulative Mertens function)", "mertens_M", "discover_mertens_value"),
    DIVISOR("D(x) = Σ_{n≤x} d(n) (the Dirichlet divisor summatory function)", "divisor_D", "discover_divisor_value"),
    PRIME("pi(x) = #{p ≤ x : p prime} (the prime-counting function)", "prime_pi", "discover_prime_value");

    public final String quantity;
    public final String boundKey;
    /** Per-paper compute tool, or null when only the autonomous frontier feeds the corpus (→ recall-only). */
    public final String discoverTool;

    FrontierDomain(String quantity, String boundKey, String discoverTool) {
        this.quantity = quantity;
        this.boundKey = boundKey;
        this.discoverTool = discoverTool;
    }
}
