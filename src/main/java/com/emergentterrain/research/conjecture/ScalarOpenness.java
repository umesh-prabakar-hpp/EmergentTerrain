package com.emergentterrain.research.conjecture;

import java.util.Map;
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
            "twin_prime_density",     // twin primes (Hardy–Littlewood)
            // 2026-06-16 (object-substrate-expansion.md run-4 rec #1) — the new per-integer objects. Run 4
            // proved they integrate (residual structure, promotions, a paper as predictor) but were never
            // SELECTED as primary topics, so the payoff is untested. Prioritising them surfaces a
            // squarefree_density investigation (→ 6/π² validation) and the first DIRECT novelty score on
            // the two uncharted objects.
            "squarefree_density",       // validation: Ω==ω → 6/π² (should rediscover the known constant)
            "richly_divisible_density", // uncharted: d > ω²
            "omega_excess_density");    // uncharted: Ω−ω ≥ 2

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

    /**
     * Phase 30 (measurement-frame-default.md) — the CLAIM-TYPE lever (tag {@code claim-type-shift}).
     * Objects with a genuine intrinsic asymptotic get a MEASUREMENT frame — the
     * {@code verify_constant}/{@code verify_oscillation}/{@code verify_growth} claim that scored &gt;0.4
     * this session (dirichlet_error O(1) envelope 0.41; omega_Omega_gap → Σ1/p(p−1) 0.53) — instead of
     * the default affine bound {@code lhs ≤ k·rhs + c}, which is a proxy-wrapper that pins novelty at
     * ~0.22 whatever the object. When the framing flag is on, {@code ResearchCycleService} frames a
     * registry object's investigation as the measurement, not a bound.
     */
    public enum MeasurementFrame { CONSTANT, OSCILLATION, GROWTH, ENVELOPE }

    /**
     * How fast the object approaches its limit, so the framing can tell the professor whether to expect a
     * clean converged value at reachable x (FAST) or only a slow trend (SLOW) — and size the scale ladder
     * accordingly. Number-theoretic limits are dominantly SLOW (~1/ln x or 1/ln ln x): a single window
     * looks "unconverged" and the result is the trend/rate, not a settled value. Only the few with a
     * fast (e.g. power-law) average convergence are FAST.
     */
    public enum Convergence { FAST, SLOW }

    /**
     * A measurement frame for an object: the kind of claim, the rhs growth-anchor ({@code GROWTH} only,
     * else null), the established target limit/constant if known (else null → the professor measures it),
     * and the {@link Convergence} class that tells the professor how to size the scale ladder. Targets are
     * only the ones the directive already states with confidence; growth constants are left null so the
     * framing never asserts an unverified value.
     */
    public record MeasurementSpec(MeasurementFrame frame, String growthScale, Double target,
                                  Convergence convergence, String secondOrder) {

        /** Compat constructor — no second-order directive (the common case). */
        public MeasurementSpec(MeasurementFrame frame, String growthScale, Double target,
                               Convergence convergence) {
            this(frame, growthScale, target, convergence, null);
        }
    }

    public static final Map<String, MeasurementSpec> MEASUREMENT_FRAMES = Map.ofEntries(
            // dirichlet O(1) envelope — needs the widest span to test boundedness at higher x → SLOW.
            Map.entry("dirichlet_error",     new MeasurementSpec(MeasurementFrame.OSCILLATION, null, null, Convergence.SLOW)),
            // omega_Omega_gap → Σ1/p(p−1): prime-sum tail, NULL'd unconverged at 2e7 → SLOW.
            Map.entry("omega_Omega_gap",     new MeasurementSpec(MeasurementFrame.CONSTANT, null, 0.77316, Convergence.SLOW)),
            // mean_divisor_excess → 2γ: the local divisor-window average converges ~x^{-1/2} → FAST.
            Map.entry("mean_divisor_excess", new MeasurementSpec(MeasurementFrame.CONSTANT, null,
                    KnownLimitRegistry.TWO_EULER_GAMMA, Convergence.FAST)),
            // omega_ek_ratio → 1: Erdős–Kac variance, glacial (~1/√ln ln x) → SLOW.
            Map.entry("omega_ek_ratio",      new MeasurementSpec(MeasurementFrame.CONSTANT, null, 1.0, Convergence.SLOW)),
            // This is an extreme-value statistic whose value changes with window design; it is a
            // finite envelope study, not evidence that a limiting constant exists.
            Map.entry("cramer_gap_ratio",    new MeasurementSpec(MeasurementFrame.ENVELOPE, null, null, Convergence.SLOW)),
            // twin_vs_prime_sq → 2C₂ (Hardy–Littlewood twin constant): a clean window-independent limit,
            // glacial (~1/ln x) → SLOW. Registered 2026-06-17 (Stage 1f) — was demoted by title-drift
            // manifest_miss (run-13 P-710, 0.697) as an UNregistered bound; now framed as a measurement.
            // 2026-07-05 (run-23 rec 2) — SECOND-ORDER re-target: the constant itself is verified
            // (P-b72c-77627776 rel-dev 0.018; P-785 repeated it at novelty 0.44 — the family's 4th
            // re-measurement). The open question is the CONVERGENCE RATE, so the directive now
            // steers to the deficit trajectory instead of a fifth constant re-measurement.
            Map.entry("twin_vs_prime_sq",    new MeasurementSpec(MeasurementFrame.CONSTANT, null, 1.32032, Convergence.SLOW,
                    "SECOND-ORDER TARGET — the limit 2*C2 ≈ 1.32032 is already VERIFIED in this room "
                    + "(rel-dev 0.018); re-measuring it is a repeat, not a result. The open question is "
                    + "the RATE: measure the deficit (1.32032 − twin_vs_prime_sq) at each ladder scale "
                    + "and report how it shrinks — does deficit(x) ≈ c/ln(x) (the Hardy–Littlewood "
                    + "secondary-term prediction), and what is the fitted c? The deficit trajectory and "
                    + "its fitted rate are the load-bearing claim; the constant itself is background.")),
            // extremal (max) statistics — noisy and slow → SLOW.
            Map.entry("max_divisor_count",   new MeasurementSpec(MeasurementFrame.GROWTH, "wigert_bound", null, Convergence.SLOW)),
            Map.entry("max_prime_gap",       new MeasurementSpec(MeasurementFrame.GROWTH, "log_sq_window_start", null, Convergence.SLOW)),
            // ln ln x normal order — the additive constant approaches ~1/ln x → SLOW.
            Map.entry("mean_omega",          new MeasurementSpec(MeasurementFrame.GROWTH, "log_log_window_start", null, Convergence.SLOW)),
            Map.entry("mean_Omega",          new MeasurementSpec(MeasurementFrame.GROWTH, "log_log_window_start", null, Convergence.SLOW)),
            Map.entry("mean_divisor_count",  new MeasurementSpec(MeasurementFrame.GROWTH, "log_window_start", null, Convergence.SLOW)));

    /** The measurement frame for {@code lhs}, or null if it has no registered intrinsic asymptotic. */
    public static MeasurementSpec measurementFrame(String lhs) {
        return lhs == null ? null : MEASUREMENT_FRAMES.get(lhs);
    }

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
