package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase 30 Wave 4 (evaluation hardening) component 4 — ensemble replication + stochastic-pass
 * semantics for {@code lhs <= k*rhs + c}.
 *
 * <p>sim #10 asks a finding to "hold across N replicates with a quantified failure rate" rather
 * than be accepted from a single fit. The room's number-theory windows are <em>deterministic</em>
 * (M(x), D(x), densities are fixed functions of x), so there is no RNG seed to vary — the honest
 * analog is <strong>K-fold cross-validation of the intercept</strong>: partition the windows into
 * K folds and, for the conjecture's already-fitted slope {@code k} (fixed), refit only the tightest
 * intercept {@code c} on the other K−1 folds, then test whether {@code lhs <= k*rhs + c} still holds
 * on the held fold (worst margin ≤ {@code 2*tol}). A bound whose intercept is an artifact of the
 * exact window set fails out-of-fold; a stable bound holds on every fold.
 *
 * <p>We refit only {@code c} (not {@code k}): re-searching the slope per fold picks whichever steep
 * {@code k} minimises the intercept on the fit fold, which then fails on any held fold containing a
 * range-boundary point — flagging even genuine monotone relations. Holding {@code k} at the
 * conjecture's value isolates the real question: does <em>this</em> bound's intercept replicate?
 *
 * <p>The <strong>pass rate</strong> = folds-held / K is the stochastic-pass score (a quantified
 * replication number, not a binary verdict); a bound is flagged when it falls below the required
 * rate. Distinct from the private holdout (component 3, fixed full {@code (k,c)} on a fixed disjoint
 * set): replication re-fits the intercept per fold, catching an unstable/overfit intercept.
 *
 * <p>Pure, stateless, deterministic (fold assignment seeded by the caller); uses the engine's
 * {@code 2*tol} refute threshold.
 */
public final class ConjectureReplicationValidator {

    private ConjectureReplicationValidator() {
    }

    /** Need at least this many paired samples per fold for a fit/test split to be meaningful. */
    static final int MIN_PER_FOLD = 2;

    /**
     * @param folds       number of CV folds actually used (reduced to fit the sample count)
     * @param foldsHeld   folds whose held-out slice satisfied the refit bound
     * @param passRate    foldsHeld / folds (1.0 = held every fold)
     * @param samplesUsed paired finite samples
     * @param flagged     true → passRate below the required minimum (replication-fragile)
     * @param reason      verdict code
     */
    public record Result(int folds, int foldsHeld, double passRate, int samplesUsed,
                         boolean flagged, String reason) {
    }

    /**
     * @param k           the conjecture's fitted slope (held fixed; only the intercept is refit)
     * @param folds       desired fold count (clamped down when there aren't enough samples)
     * @param tol         slack tolerance; a held fold must satisfy worst margin ≤ {@code 2*tol}
     * @param minPassRate keep iff {@code passRate >= minPassRate}; below it the bound is flagged
     * @param seed        deterministic fold-assignment seed
     */
    public static Result evaluate(List<Map<String, Double>> samples, String lhs, String rhs,
                                  double k, int folds, double tol, double minPassRate, long seed) {
        if (samples == null || lhs == null || rhs == null) {
            return new Result(0, 0, 1.0, 0, false, "insufficient_samples");
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Map<String, Double> s : samples) {
            Double x = s.get(lhs);
            Double y = s.get(rhs);
            if (x != null && y != null && Double.isFinite(x) && Double.isFinite(y)) {
                xs.add(x);
                ys.add(y);
            }
        }
        int n = xs.size();
        int kf = Math.min(Math.max(folds, 2), n / MIN_PER_FOLD);
        if (kf < 2) {
            return new Result(0, 0, 1.0, n, false, "insufficient_samples");
        }

        // Deterministic shuffle; fold f owns shuffled positions where pos % kf == f (balanced).
        int[] pos = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i] = i;
        }
        Random rng = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = pos[i];
            pos[i] = pos[j];
            pos[j] = t;
        }

        int held = 0;
        for (int f = 0; f < kf; f++) {
            // Tightest valid intercept on the fit folds: c = max(lhs - k*rhs).
            double cFit = Double.NEGATIVE_INFINITY;
            boolean anyFit = false;
            for (int p = 0; p < n; p++) {
                if (p % kf == f) {
                    continue;   // held-out
                }
                int idx = pos[p];
                double v = xs.get(idx) - k * ys.get(idx);
                if (v > cFit) {
                    cFit = v;
                }
                anyFit = true;
            }
            // Worst margin on the held fold under the refit intercept.
            double worst = Double.NEGATIVE_INFINITY;
            boolean anyTest = false;
            for (int p = 0; p < n; p++) {
                if (p % kf != f) {
                    continue;
                }
                int idx = pos[p];
                double margin = xs.get(idx) - (k * ys.get(idx) + cFit);
                if (margin > worst) {
                    worst = margin;
                }
                anyTest = true;
            }
            if (anyFit && anyTest && worst <= 2 * tol) {
                held++;
            }
        }
        double passRate = (double) held / kf;
        boolean flagged = passRate < minPassRate;
        return new Result(kf, held, passRate, n, flagged,
                flagged ? "replication_fragile" : "ok");
    }
}
