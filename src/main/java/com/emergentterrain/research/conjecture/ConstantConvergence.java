package com.emergentterrain.research.conjecture;

/**
 * 2026-06-10 (lever D, frame 1 — constant-convergence). The result-frame for a scalar whose real
 * content is a LIMIT, not a one-sided bound: a normalized ratio that plateaus to a constant
 * (e.g. {@code twin_vs_prime_sq} → the Hardy–Littlewood twin constant 2C₂ ≈ 1.32032;
 * {@code cramer_gap_ratio} → a Cramér-type constant). The conjecture pipeline's {@code lhs ≤ k·rhs + c}
 * frame can only express "q → C" as a degenerate {@code k=0} bound its own identifiability /
 * rhs-near-constant gates reject — so such quantities never become survivors despite being genuinely
 * interesting. This is the two-sided assessment ({@code |q − C|} small) those quantities actually need.
 *
 * <p>Pure + static so it unit-tests without a sidecar; the {@code verify_constant} tool feeds it the
 * per-window scalar values from {@link ConjectureVerifier#sampleWindows}.
 */
public final class ConstantConvergence {

    private ConstantConvergence() {}

    /**
     * @param mean          arithmetic mean of the per-window values — the LIMIT estimate
     * @param min           smallest window value
     * @param max           largest window value
     * @param estimate      the constant being tested against: {@code target} if supplied, else {@code mean}
     * @param worstDeviation the largest {@code |value − estimate|} over the windows
     * @param worstWindowStart the window start at which {@code worstDeviation} occurred
     * @param relativeSpread {@code worstDeviation / |estimate|} — per-window SAMPLING NOISE (informative,
     *                       not a convergence test): a sparse quantity (twin primes) is noisy per window
     *                       even when its mean has converged
     * @param meanRelDeviation {@code |mean − target| / |target|} when a target is given (0 otherwise) —
     *                       the actual CONVERGENCE signal: does the average approach the limit?
     * @param converged     with a target: {@code meanRelDeviation ≤ tol} (the mean reached the limit;
     *                       window noise is averaged out). Without a target: {@code relativeSpread ≤ tol}
     *                       (the values have settled).
     */
    public record Assessment(double mean, double min, double max, double estimate,
                             double worstDeviation, long worstWindowStart,
                             double relativeSpread, double meanRelDeviation, boolean converged) {}

    private static final double EPS = 1e-12;

    /**
     * Assess whether {@code values} (per-window scalar readings, with their window starts) cluster around
     * a constant. With a {@code target} (a conjectured limit such as 2C₂), tests two-sided convergence to
     * it; without one, estimates the limit as the mean and reports the spread (is it settling?).
     *
     * @param values  per-window scalar values (must be non-empty and same length as {@code starts})
     * @param starts  per-window start integers, parallel to {@code values}
     * @param target  the conjectured constant, or {@code null} to estimate it empirically
     * @param tol     relative tolerance: converged iff {@code worstDeviation / |estimate| ≤ tol}
     */
    public static Assessment assess(double[] values, long[] starts, Double target, double tol) {
        if (values == null || values.length == 0 || starts == null || starts.length != values.length) {
            throw new IllegalArgumentException("values/starts must be non-empty and equal length");
        }
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / values.length;
        double estimate = target != null ? target : mean;
        double worstDev = 0;
        long worstStart = starts[0];
        for (int i = 0; i < values.length; i++) {
            double dev = Math.abs(values[i] - estimate);
            if (dev > worstDev) {
                worstDev = dev;
                worstStart = starts[i];
            }
        }
        double relSpread = worstDev / Math.max(Math.abs(estimate), EPS);
        // Convergence is about the MEAN reaching the limit, not every noisy window: a sparse quantity
        // (twin primes in a 5k window) deviates per-window even once its average has converged. With a
        // target, judge |mean − target|; without one, the empirical limit IS the mean, so fall back to
        // the spread (have the values settled?).
        double meanRelDev = target != null ? Math.abs(mean - target) / Math.max(Math.abs(target), EPS) : 0.0;
        boolean converged = target != null ? (meanRelDev <= tol) : (relSpread <= tol);
        return new Assessment(mean, min, max, estimate, worstDev, worstStart, relSpread, meanRelDev, converged);
    }
}
