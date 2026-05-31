package com.emergentterrain.research.discovery;

import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 2 — the stall predictor
 * (frontier-escalation-loop.md §3). From the observed per-rung discovery cost
 * {@code (x, elapsedMs)}, fit a power law {@code cost ≈ a·x^p} by least squares in
 * log-log space, then solve {@code a·x_c^p = budget} for the **compute frontier**
 * {@code x_c} — the scale beyond which a rung's computation is predicted to exceed
 * the budget (and so would stall). Compare {@code x_c} to the configured
 * **science frontier** {@code x_s} (where the question actually gets decided) to
 * flag whether computation stalls *before the answer*.
 *
 * <p>Pure / static — the exponent is learned empirically (more honest than
 * assuming O(x) vs O(x^{2/3})), so whatever method discovery produced, its actual
 * cost scaling drives the prediction. Needs ≥2 positive observations with distinct
 * x; otherwise returns an {@link StallPrediction#invalid} result.
 */
public final class StallPredictor {

    private StallPredictor() {}

    public static StallPrediction predict(List<CostObservation> observations,
                                          double budgetMs, double scienceFrontier) {
        List<CostObservation> pts = observations.stream()
                .filter(o -> o.x() > 0 && o.elapsedMs() > 0)
                .toList();
        if (pts.size() < 2) {
            return StallPrediction.invalid("need >=2 positive cost points (have " + pts.size() + ")");
        }
        int n = pts.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (CostObservation o : pts) {
            double lx = Math.log(o.x());
            double ly = Math.log(o.elapsedMs());
            sx += lx; sy += ly; sxx += lx * lx; sxy += lx * ly;
        }
        double denom = n * sxx - sx * sx;
        if (denom == 0) {
            return StallPrediction.invalid("degenerate cost fit (all x equal)");
        }
        double p = (n * sxy - sx * sy) / denom;     // exponent
        double lnA = (sy - p * sx) / n;             // ln(a)
        if (p <= 0 || !Double.isFinite(p) || !Double.isFinite(lnA)) {
            return StallPrediction.invalid(String.format("cost not increasing with x (p=%.3f)", p));
        }
        double a = Math.exp(lnA);
        double xc = Math.exp((Math.log(budgetMs) - lnA) / p);   // solve a*xc^p = budget
        boolean stallsBeforeScience = scienceFrontier > 0 && xc < scienceFrontier;
        String basis = String.format(
                "cost ~= %.3g * x^%.3f (n=%d); compute frontier x_c ~= %.3g at budget=%.0fms%s",
                a, p, n, xc, budgetMs,
                scienceFrontier > 0
                        ? String.format(", science frontier x_s=%.3g -> stalls-before-answer=%b",
                                scienceFrontier, stallsBeforeScience)
                        : "");
        return new StallPrediction(true, p, a, xc, stallsBeforeScience, basis);
    }
}
