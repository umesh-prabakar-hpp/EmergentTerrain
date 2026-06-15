package com.emergentterrain.research.conjecture;

import java.util.Set;

/**
 * Phase 30 — open-behavior generation, Stage G1 (open-behavior-generation-activation.md). The
 * openness taxonomy of the math scalar vocabulary, lifted from prose (conjectureDirective step 9)
 * into machine-readable data so the GENERATOR can prefer open-behavior conjectures over affine
 * bounds on settled objects — the upstream novelty blocker (corpus is ~100% known-result
 * verification; generation is the blocker).
 *
 * <p><b>Classes.</b>
 * <ul>
 *   <li>{@code SETTLED_LEADING_ORDER} — a textbook leading-order law or known limit (mean_omega /
 *       mean_Omega ~ ln ln x; mean_divisor_count ~ ln x; the densities tend to known constants).
 *       An affine {@code lhs <= k*rhs + c} with a FLAT feature rhs is trivial / wrong-frame for these:
 *       their real content is a growth-anchor bound (recovering the constant as the intercept) or a
 *       non-affine frame (verify_constant), not a flat bound.</li>
 *   <li>everything else is left UNCLASSIFIED here (NEUTRAL) — open objects (dirichlet_error,
 *       cramer_gap_ratio, max_prime_gap, mertens_delta), growing objects (max_divisor_count), and
 *       second-order residuals (omega_ek_ratio, mean_divisor_excess, omega_Omega_gap) are the targets
 *       we want to KEEP, so G1 only needs to recognise the settled class to demote it.</li>
 * </ul>
 *
 * <p>G1's only behaviour is the demotion predicate {@link #isTrivialAffineOnSettled}: drop a SLOPED
 * affine bound on a settled-leading-order LHS unless its rhs is a growth anchor (a window-geometry
 * scalar, which makes the bound track the leading order and recover its constant). It deliberately
 * KEEPS (a) {@code k≈0} constant bounds — the real density-limit claim the triviality filter already
 * preserves — and (b) growth-anchor bounds. Pure, stateless, deterministic on {@code (lhs, rhs, k)}.
 */
public final class ScalarOpenness {

    /**
     * Scalars whose leading-order behaviour is textbook (a known law or limit), so a flat affine
     * bound on them is trivial. Lifted from conjectureDirective step 9. Open / growing / second-order
     * scalars are intentionally ABSENT — they are the targets G1 promotes by leaving them untouched.
     */
    public static final Set<String> SETTLED_LEADING_ORDER = Set.of(
            "mean_omega",            // ~ ln ln x (Hardy–Ramanujan)
            "mean_Omega",            // ~ ln ln x (Hardy–Ramanujan)
            "mean_divisor_count",    // ~ ln x (Dirichlet)
            "abundant_density",      // → ≈ 0.2476
            "deficient_density",     // → ≈ 0.7524
            "prime_density",         // → 0 (PNT)
            "happy_density");        // base-10 density (settled within a base)

    /**
     * Phase 30 G4 (open-behavior-generation-activation.md) — the actively-progressing open /
     * second-order / growth-capture LHS that selection should PROMOTE to the front of the queue, so
     * the now-clean pool is actually drawn from (the bottleneck after G1 cleaned generation was
     * selection — growing-LHS topics surfaced only by luck). These are the targets with un-captured
     * progress to make: the open Dirichlet exponent, the growth-anchor captures (Cramér / Wigert), and
     * the second-order constants (2γ, 2C₂, the Erdős–Kac variance, the prime-power gap). NOT included:
     * settled leading orders (their flat bounds are G1-pruned; their second-order residuals ARE here),
     * and mertens_delta / mertens_per_sqrt_W — open but finitely-unresolvable AND already measured
     * (the envelope is a persisted measured-invariant), so they fall to default priority — the
     * deliberate down-weight of the over-selected Mertens envelope.
     */
    public static final Set<String> PRIORITY_TARGETS = Set.of(
            "dirichlet_error",        // open: the Dirichlet divisor exponent (novelty flagship)
            "max_prime_gap",          // growth-anchor capture (Cramér ln²x)
            "max_divisor_count",      // growth-anchor capture (Wigert)
            "omega_Omega_gap",        // second-order → Σ 1/(p(p-1)) ≈ 0.77316
            "omega_ek_ratio",         // Erdős–Kac variance → 1
            "mean_divisor_excess",    // second-order → 2γ
            "cramer_gap_ratio",       // Cramér-normalized record gap (open)
            "twin_vs_prime_sq",       // Hardy–Littlewood → 2C₂
            "twin_prime_density");    // twin primes (Hardy–Littlewood)

    /**
     * Phase 30 (review-2026-06-15e Rec 2) — open targets that OSCILLATE around 0, so an affine bound on
     * them has an unpinned slope BY NATURE (Δ(x)/x^{1/4} swings ±, no one-sided slope). The FIM
     * identifiability filter ({@code slope_unpinned}) therefore retires ALL their affine seeds, leaving
     * the LHS unreachable through the conjecture queue — so the open Dirichlet target never surfaces and
     * the dirichlet_error scaling fix can't be tested. Exempting these from the FIM drop (the slope
     * being unpinned is expected, not a flaw — same logic as growth anchors vs the knob filter) keeps a
     * servable seed live; when served, the professor pivots to verify_oscillation (the right frame).
     */
    public static final Set<String> OSCILLATING_TARGETS = Set.of("dirichlet_error");

    private ScalarOpenness() {
    }

    /** True iff the scalar is an open oscillating target (FIM slope-unpinned is its nature). */
    public static boolean isOscillatingTarget(String scalar) {
        return scalar != null && OSCILLATING_TARGETS.contains(scalar);
    }

    /** True iff the scalar has a textbook leading-order law / known limit (the settled class). */
    public static boolean isSettledLeadingOrder(String scalar) {
        return scalar != null && SETTLED_LEADING_ORDER.contains(scalar);
    }

    /**
     * G4 selection rank for an LHS scalar — LOWER sorts FIRST. {@code 0} = a priority open/second-order
     * /growth-capture target ({@link #PRIORITY_TARGETS}); {@code 1} = everything else (settled leading
     * orders whose flat bounds are pruned, the over-measured Mertens envelope, neutral scalars). Used as
     * the primary selection key (under a flag) so high-value targets are drawn before the rest, with the
     * existing rotation / clade order as the within-bucket tiebreak. Null-safe.
     */
    public static int selectionRank(String lhs) {
        return lhs != null && PRIORITY_TARGETS.contains(lhs) ? 0 : 1;
    }

    /**
     * G1 demotion predicate. True iff this is a SLOPED affine bound ({@code k≠0}) on a
     * settled-leading-order LHS whose rhs is a FLAT per-window feature (NOT a growth anchor) — the
     * trivial-refutation class that dominates the corpus. Keeps {@code k≈0} constant bounds (the real
     * density-limit claim) and growth-anchor bounds (rhs ∈ {@link
     * ConjectureTrivialityFilter#WINDOW_GEOMETRY_SCALARS}, which let the bound track the leading order
     * and recover its constant as the intercept).
     */
    public static boolean isTrivialAffineOnSettled(Inequality ineq) {
        return ineq != null
                && isSettledLeadingOrder(ineq.lhs())
                && Math.abs(ineq.k()) > ConjectureTrivialityFilter.K_EPSILON
                && !ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS.contains(ineq.rhs());
    }
}
