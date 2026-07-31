package com.emergentterrain.research.conjecture;

/**
 * 2026-06-10 (lever D, frame 2 — oscillation / sign-change). The result-frame for a scalar that
 * OSCILLATES around zero, where the content is the sign-change structure and the bounded ENVELOPE, not a
 * one-sided bound. The canonical case is {@code mertens_delta} — the Mertens function M(x), which changes
 * sign infinitely often. In this application {@code mertens_delta} is instead a window increment
 * {@code M(window_end)-M(window_start)}, so its local finite-envelope normalization is
 * {@code sqrt(window_size)}. That measurement must not be presented as the classical summatory
 * Mertens/RH statement. A {@code lhs ≤ k·rhs + c} bound is the wrong object — it refutes the
 * moment the signed quantity dips negative or spikes. This measures: sign changes, amplitude,
 * near-zero mean, and (given a scale) the envelope {@code max |lhs|/scale}.
 *
 * <p>Pure + static so it unit-tests without a sidecar; the {@code verify_oscillation} tool feeds it the
 * per-window {@code lhs} values (and optional per-window scale values).
 */
public final class OscillationAssessment {

    private OscillationAssessment() {}

    /**
     * @param signChanges    count of consecutive windows whose values have opposite sign
     * @param amplitude      {@code max |value|} over the windows
     * @param mean           arithmetic mean (≈ 0 for a true oscillation)
     * @param meanAbs        mean of {@code |value|}
     * @param envelope       {@code max |value| / scale} if a scale was supplied, else NaN
     * @param ampWindowStart the window start at which {@code amplitude} occurred
     * @param oscillates     true when there is ≥1 sign change AND the mean is small relative to amplitude
     */
    public record Assessment(int signChanges, double amplitude, double mean, double meanAbs,
                             double envelope, long ampWindowStart, boolean oscillates) {}

    /**
     * Whether an oscillation is genuinely centred near zero — {@code |mean|} small relative to the mean
     * ABSOLUTE value (the typical magnitude). Distinct from {@link Assessment#oscillates()}, which checks
     * the mean against the AMPLITUDE (the peak): a one-sign-biased series (e.g. {@code dirichlet_error} =
     * Δ(x)/x^{1/4}, which is genuinely net-positive at reachable x even though its sign-symmetric
     * oscillation is an x→∞ property) passes the amplitude test while averaging far from 0. The per-call
     * label uses this tighter test so it stops asserting "centred near 0" for a clearly biased series.
     * Degenerate {@code meanAbs ≤ 0} → centred (nothing to bias).
     */
    public static boolean centredNearZero(double mean, double meanAbs) {
        return meanAbs <= 0.0 || Math.abs(mean) <= 0.5 * meanAbs;
    }

    private static final double EPS = 1e-12;

    /**
     * Assess oscillation of {@code values}. {@code scale} (parallel, e.g. √window_start) is optional: when
     * non-null the envelope {@code max |value|/scale} is reported (NaN otherwise). A "true oscillation"
     * needs at least one sign change and a mean small relative to its amplitude (centred on 0, not a
     * one-sided quantity that merely wiggles).
     */
    public static Assessment assess(double[] values, long[] starts, double[] scale) {
        if (values == null || values.length == 0 || starts == null || starts.length != values.length) {
            throw new IllegalArgumentException("values/starts must be non-empty and equal length");
        }
        if (scale != null && scale.length != values.length) {
            throw new IllegalArgumentException("scale must be null or parallel to values");
        }
        int signChanges = 0;
        double amplitude = 0, sum = 0, sumAbs = 0, envelope = Double.NaN;
        long ampStart = starts[0];
        int prevSign = 0;
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            sum += v;
            sumAbs += Math.abs(v);
            if (Math.abs(v) > amplitude) {
                amplitude = Math.abs(v);
                ampStart = starts[i];
            }
            int sign = v > EPS ? 1 : (v < -EPS ? -1 : 0);
            if (sign != 0 && prevSign != 0 && sign != prevSign) {
                signChanges++;
            }
            if (sign != 0) prevSign = sign;
            if (scale != null) {
                double s = Math.abs(scale[i]);
                if (s > EPS) {
                    double env = Math.abs(v) / s;
                    if (Double.isNaN(envelope) || env > envelope) envelope = env;
                }
            }
        }
        double mean = sum / values.length;
        double meanAbs = sumAbs / values.length;
        boolean oscillates = signChanges >= 1 && Math.abs(mean) <= 0.5 * Math.max(amplitude, EPS);
        return new Assessment(signChanges, amplitude, mean, meanAbs, envelope, ampStart, oscillates);
    }
}
