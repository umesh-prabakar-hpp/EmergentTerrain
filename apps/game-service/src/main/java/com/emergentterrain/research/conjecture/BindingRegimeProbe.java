package com.emergentterrain.research.conjecture;

/**
 * Binding-regime coverage check for {@code verify_conjecture} bounds of the
 * form {@code lhs <= k * rhs + c}.
 *
 * <p><b>Why this exists.</b> In room b72c4e10 a paper (P-b72c-15) "confirmed"
 * the bound {@code mertens_per_sqrt_W <= 2.0 * log_window_start - 7.7621} by
 * sampling only large window starts (≥ 200,000), where the ceiling
 * {@code k*rhs+c} is comfortably positive. Two sibling papers that probed
 * small starts (2,003 / 3,001) correctly <i>refuted</i> the same bound: at
 * small start the ceiling goes negative while the lhs stays O(1) positive, so
 * the violation is structural. Nothing in the pipeline required a probe of the
 * regime where the bound actually binds, so a confirmatory-only trace earned a
 * SUPPORTED classification (see {@code research/b72c4e10/REVIEW_REPORT.md}).
 *
 * <p><b>Scope — deliberately narrow.</b> "Smallest start is the binding
 * regime" is only deterministically true when the rhs scalar increases
 * monotonically with {@code window_start} <i>and</i> {@code k > 0}, so that the
 * ceiling is tightest at the smallest start. In the math vocabulary
 * ({@link MathInvariants}) the only such scalar is {@code log_window_start};
 * every other scalar is a function of the window's <i>content</i> (densities,
 * Mertens delta) or its <i>size</i> (sqrt/log_window_size), where a small-start
 * probe could fabricate a spurious refutation. We therefore restrict the probe
 * to {@code log_window_start} with {@code k > 0} and stay silent otherwise — a
 * missed probe is acceptable; a false refutation is not.
 *
 * <p>Pure and stateless so it is unit-testable without Spring, the sidecar, or
 * Mockito (the dev shell's JDK can't instantiate Mockito-mocked beans).
 */
public final class BindingRegimeProbe {

    private BindingRegimeProbe() {}

    /** The unique rhs scalar that is monotone-increasing in {@code window_start}. */
    public static final String MONOTONE_IN_START_SCALAR = "log_window_start";

    /**
     * True iff a bound {@code lhs <= k*rhs+c} with this {@code rhs}/{@code k}
     * has its tightest ceiling at the smallest window start, so the binding
     * regime can be probed deterministically.
     */
    public static boolean appliesTo(String rhs, double k) {
        return k > 0 && MONOTONE_IN_START_SCALAR.equals(rhs);
    }

    /**
     * True iff a boundary probe at {@code probeStart} is worth running: the
     * bound is start-monotone (see {@link #appliesTo}) and {@code probeStart}
     * is a valid start strictly below the smallest already-requested start, so
     * the probe tests a tighter (lower-ceiling) region the caller did not
     * already cover.
     */
    public static boolean shouldProbe(String rhs, double k,
                                      long smallestRequestedStart, long probeStart) {
        return appliesTo(rhs, k)
                && probeStart >= 0
                && probeStart < smallestRequestedStart;
    }
}
