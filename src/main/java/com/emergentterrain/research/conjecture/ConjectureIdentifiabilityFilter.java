package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 1 — Fisher-information identifiability gate (sim #5): drop bounds that claim a
 * slope the data does not pin.
 *
 * <p>A bound {@code lhs <= k*rhs + c} with {@code k != 0} asserts that {@code rhs} matters. The
 * Fisher information for the slope of the underlying linear model {@code lhs = β·rhs + α + noise}
 * is {@code Σ(rhs - mean)² / σ²}; the slope is identifiable only when that is well away from
 * singular. We fit the OLS slope {@code β̂}, its residual scale {@code σ̂}, and its standard error
 * {@code SE(β̂) = σ̂ / √Σ(rhs - mean)²}, then look at the <b>relative standard error</b>
 * {@code SE(β̂)/|β̂|}: if it exceeds a threshold, the data is consistent with {@code β = 0} — the
 * slope is <i>not pinned</i>, so the conjecture's non-zero {@code k} is an over-parameterisation
 * (it would fit equally as a constant bound). A degenerate {@code rhs} (no variation → singular
 * Fisher information) is the limiting case.
 *
 * <p>Conservatively scoped: only bounds that actually claim a slope ({@code |k| > K_EPSILON}) are in
 * scope — a {@code k≈0} constant bound pins nothing by design and is handled by
 * {@link ConjectureTrivialityFilter}, not flagged here. Pure, stateless, deterministic; uses the
 * accumulated window samples (no permutation — this is the cheap parametric W1 check, distinct from
 * the W4 stochastic null). Wired flag-gated OFF via
 * {@code app.research.conjectureMiner.identifiabilityEnabled}.
 */
public final class ConjectureIdentifiabilityFilter {

    /** Below this paired-sample count the standard error isn't trustworthy — never flag. */
    static final int MIN_SAMPLES = 6;

    private ConjectureIdentifiabilityFilter() {
    }

    /**
     * @param slope      OLS slope β̂ of lhs on rhs
     * @param relStdErr  SE(β̂)/|β̂| (POSITIVE_INFINITY when β̂≈0 or rhs degenerate)
     * @param flagged    true → slope not pinned (over-parameterised) → drop
     * @param reason     verdict code
     */
    public record Result(double slope, double relStdErr, boolean flagged, String reason) {
    }

    /**
     * @param conjectureK the bound's fitted slope; the gate only applies when |k| &gt; K_EPSILON
     * @param maxRelStdErr flag when the OLS slope's relative standard error exceeds this
     */
    public static Result evaluate(List<Map<String, Double>> samples, String lhs, String rhs,
                                  double conjectureK, double maxRelStdErr) {
        if (samples == null || lhs == null || rhs == null) {
            return new Result(0.0, 0.0, false, "insufficient_samples");
        }
        // A constant bound claims no slope — out of scope (TrivialityFilter owns those).
        if (Math.abs(conjectureK) <= ConjectureTrivialityFilter.K_EPSILON) {
            return new Result(0.0, 0.0, false, "constant_bound_out_of_scope");
        }
        List<Double> xs = new ArrayList<>();   // lhs (response)
        List<Double> ys = new ArrayList<>();   // rhs (predictor)
        for (Map<String, Double> s : samples) {
            Double x = s.get(lhs);
            Double y = s.get(rhs);
            if (x != null && y != null && Double.isFinite(x) && Double.isFinite(y)) {
                xs.add(x);
                ys.add(y);
            }
        }
        int n = xs.size();
        if (n < MIN_SAMPLES) {
            return new Result(0.0, 0.0, false, "insufficient_samples");
        }

        double meanY = mean(ys);
        double meanX = mean(xs);
        double sYY = 0;   // Σ(rhs - meanY)²  — the (unscaled) Fisher information for the slope
        double sXY = 0;
        for (int i = 0; i < n; i++) {
            double dy = ys.get(i) - meanY;
            sYY += dy * dy;
            sXY += dy * (xs.get(i) - meanX);
        }
        if (sYY <= 0.0) {
            // rhs has no variation → Fisher information singular → slope unidentifiable.
            return new Result(0.0, Double.POSITIVE_INFINITY, true, "rhs_degenerate");
        }
        double beta = sXY / sYY;
        double alpha = meanX - beta * meanY;
        double ssr = 0;
        for (int i = 0; i < n; i++) {
            double resid = xs.get(i) - (alpha + beta * ys.get(i));
            ssr += resid * resid;
        }
        double sigma2 = ssr / Math.max(1, n - 2);     // residual variance (n-2 dof)
        double seBeta = Math.sqrt(sigma2 / sYY);       // SE(β̂) = σ̂ / √Σ(rhs-mean)²
        double relSe = beta == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(seBeta / beta);
        boolean flagged = relSe > maxRelStdErr;
        return new Result(beta, relSe, flagged, flagged ? "slope_unpinned" : "ok");
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }
}
