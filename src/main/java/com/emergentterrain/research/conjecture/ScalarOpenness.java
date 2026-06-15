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

    private ScalarOpenness() {
    }

    /** True iff the scalar has a textbook leading-order law / known limit (the settled class). */
    public static boolean isSettledLeadingOrder(String scalar) {
        return scalar != null && SETTLED_LEADING_ORDER.contains(scalar);
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
