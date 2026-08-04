package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase 30 Wave 4 (evaluation hardening) — generative stochastic null for a candidate
 * bound {@code lhs <= k*rhs + c}.
 *
 * <p>A one-sided linear bound is only a <em>real constraint</em> (rather than a
 * marginal-range artifact that {@code k} and {@code c} absorb) if {@code lhs} and
 * {@code rhs} are actually associated across the substrate samples. We test the
 * no-effect null by <strong>permuting the rhs column</strong> across samples — breaking
 * the lhs↔rhs pairing while preserving both marginals — and comparing the observed
 * association {@code |Spearman ρ|} against the permutation ensemble. A bound whose
 * association does not beat the null (p &gt; α) holds only because of the marginal ranges,
 * not because lhs tracks rhs, and is flagged as spurious.
 *
 * <p>Also applies a <strong>coefficient-of-variation (Fano-style) floor</strong>: a
 * near-constant rhs makes {@code k*rhs} a constant, so the "bound" degenerates to
 * {@code lhs <= const} — flagged regardless of the permutation test.
 *
 * <p>Pure, stateless, domain-neutral, and deterministic: the permutation RNG is seeded by
 * the caller (the miner derives a fixed seed from the conjecture key), so the same inputs
 * always yield the same verdict — no {@code Math.random}. Same standalone shape as the
 * Wave-1 gates ({@link ConjectureTrivialityFilter}, {@link ConjectureDimensionFilter}).
 *
 * <p><strong>First component of Wave 4.</strong> The {@link Result#pValue()} it emits is
 * the per-conjecture significance the later multiple-comparisons-correction slice
 * (Bonferroni/BH over the surviving denominator) will consume; the private-validator /
 * gaming-rate and seed-replication slices are separate follow-ons in the same lane.
 */
public final class ConjectureStochasticNull {

    private ConjectureStochasticNull() {
    }

    /** Below this paired-sample count the permutation test has no power — never flag. */
    static final int MIN_SAMPLES = 6;

    /**
     * @param observedAbsRho |Spearman ρ| of the real lhs↔rhs pairing
     * @param pValue         add-one-smoothed permutation p (fraction of null |ρ| ≥ observed)
     * @param rhsCv          coefficient of variation of rhs over the samples
     * @param flagged        true → the bound is spurious (does not beat the null, or degenerate)
     * @param reason         machine-readable verdict code (the {@code droppedByKnob} suffix)
     */
    public record Result(double observedAbsRho, double pValue, double rhsCv,
                         boolean flagged, String reason) {
    }

    /**
     * Evaluate the bound {@code lhs <= k*rhs + c} against the stochastic null.
     *
     * @param samples      per-window scalar maps the miner already materialised
     * @param lhs          left-hand scalar name
     * @param rhs          right-hand scalar name
     * @param permutations null-ensemble size (e.g. 999)
     * @param alpha        significance threshold; flag when p &gt; alpha
     * @param minCv        coefficient-of-variation floor on rhs; flag when below
     * @param seed         deterministic permutation seed
     */
    public static Result evaluate(List<Map<String, Double>> samples, String lhs, String rhs,
                                  int permutations, double alpha, double minCv, long seed) {
        if (samples == null || lhs == null || rhs == null) {
            return new Result(0.0, 1.0, 0.0, false, "insufficient_samples");
        }
        // Paired extraction — keep only samples where both scalars are present and finite.
        List<Double> xs = new ArrayList<>(samples.size());
        List<Double> ys = new ArrayList<>(samples.size());
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
            return new Result(0.0, 1.0, 0.0, false, "insufficient_samples");
        }

        double rhsCv = coefficientOfVariation(ys);
        if (rhsCv < minCv) {
            return new Result(0.0, 1.0, rhsCv, true, "rhs_near_constant_cv");
        }

        double[] xRank = ranks(xs);
        double[] yRank = ranks(ys);
        double observed = Math.abs(pearson(xRank, yRank));

        // Permutation null: shuffle the rhs ranks, recompute |ρ|. Add-one smoothing keeps
        // p strictly in (0,1] so a perfect real fit never gets a literal p=0.
        Random rng = new Random(seed);
        double[] permuted = yRank.clone();
        int atLeastAsExtreme = 0;
        for (int b = 0; b < permutations; b++) {
            shuffle(permuted, rng);
            if (Math.abs(pearson(xRank, permuted)) >= observed) {
                atLeastAsExtreme++;
            }
        }
        double p = (atLeastAsExtreme + 1.0) / (permutations + 1.0);
        boolean flagged = p > alpha;
        return new Result(observed, p, rhsCv, flagged,
                flagged ? "null_not_beaten" : "ok");
    }

    /** Average-rank vector (ties share the mean of their rank positions). */
    static double[] ranks(List<Double> v) {
        int n = v.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v.get(a), v.get(b)));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v.get(idx[j + 1]).doubleValue() == v.get(idx[i]).doubleValue()) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1.0;   // 1-based average rank over the tie block
            for (int k = i; k <= j; k++) {
                r[idx[k]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    /** Pearson correlation; 0 when either side has zero variance (degenerate → no association). */
    static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= n;
        mb /= n;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            cov += da * db;
            va += da * da;
            vb += db * db;
        }
        if (va <= 0.0 || vb <= 0.0) {
            return 0.0;
        }
        return cov / Math.sqrt(va * vb);
    }

    /** |std / mean|; 0 when mean is 0 (treated as degenerate). */
    static double coefficientOfVariation(List<Double> v) {
        int n = v.size();
        double mean = 0;
        for (double x : v) {
            mean += x;
        }
        mean /= n;
        if (mean == 0.0) {
            return 0.0;
        }
        double var = 0;
        for (double x : v) {
            double d = x - mean;
            var += d * d;
        }
        var /= n;
        return Math.abs(Math.sqrt(var) / mean);
    }

    /** Fisher–Yates in place. */
    private static void shuffle(double[] a, Random rng) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
