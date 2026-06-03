package com.emergentterrain.research.conjecture;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 4 (evaluation hardening) component 3 — private held-out validator +
 * gaming-rate metric (anti-HARKing).
 *
 * <p>The conjecture engine fits {@code (k, c)} for {@code lhs <= k*rhs + c} on the public
 * mining windows (the {@code RUNS} offsets), and the paper pipeline re-checks on windows
 * the author LLM picks via {@code verify_conjecture} — but both of those window sets are
 * <em>visible</em> to the thing being judged, so a bound can pass by being tuned to (or
 * cherry-picked on) the offsets it knows about. This validator evaluates a surviving bound
 * on a <strong>private</strong> set of windows whose offsets are disclosed to neither the
 * fitter (never fed to {@link ConjectureEngine}) nor the author (disjoint from the offsets
 * named in the {@code verify_conjecture} tool prompt). A bound that holds on the public
 * windows but is refuted on the private ones "passed public, failed private" — overfit /
 * gamed, not a real constraint.
 *
 * <p>The <strong>gaming rate</strong> = (failed-private) / (tested) is the headline metric:
 * a high rate means the public validation is being gamed and the corpus needs hardening.
 *
 * <p>Pure, stateless, deterministic — the caller supplies the private samples (materialised
 * from the held-out offsets) and the tolerance. Same standalone shape as the other Wave-1/4
 * gates; uses the same refute threshold {@code 2*tol} as {@link ConjectureEngine#update}.
 */
public final class PrivateHoldoutValidator {

    private PrivateHoldoutValidator() {
    }

    /**
     * @param worstMargin      max over private samples of {@code lhs - (k*rhs + c)}
     *                         (NEGATIVE_INFINITY when no usable samples)
     * @param samplesEvaluated count of private samples where both scalars were present/finite
     * @param failedHoldout    true → worstMargin exceeds {@code 2*tol} → passed public, failed private
     */
    public record Verdict(double worstMargin, int samplesEvaluated, boolean failedHoldout) {
    }

    /**
     * Evaluate {@code lhs <= k*rhs + c} on private held-out samples.
     *
     * @param tol slack tolerance; the bound fails when the worst margin exceeds {@code 2*tol}
     *            (mirror {@link ConjectureEngine#TOL} so private and public use one threshold)
     */
    public static Verdict evaluate(String lhs, String rhs, double k, double c,
                                   List<Map<String, Double>> privateSamples, double tol) {
        if (privateSamples == null || lhs == null || rhs == null) {
            return new Verdict(Double.NEGATIVE_INFINITY, 0, false);
        }
        double worst = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (Map<String, Double> s : privateSamples) {
            Double x = s.get(lhs);
            Double y = s.get(rhs);
            if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) {
                continue;
            }
            double margin = x - (k * y + c);
            if (margin > worst) {
                worst = margin;
            }
            n++;
        }
        if (n == 0) {
            // No evidence on the private windows — don't accuse (e.g. scalar absent there).
            return new Verdict(Double.NEGATIVE_INFINITY, 0, false);
        }
        return new Verdict(worst, n, worst > 2 * tol);
    }

    /** Gaming rate = fraction of tested bounds that passed public but failed the private holdout. */
    public static double gamingRate(int tested, int gamed) {
        return tested <= 0 ? 0.0 : (double) gamed / tested;
    }
}
