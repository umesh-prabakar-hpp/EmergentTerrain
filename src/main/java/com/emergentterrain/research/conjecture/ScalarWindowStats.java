package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2026-06-05 — pure per-window scalar statistics for the {@code scalar_window_stats}
 * professor tool: min / max (with the achieving window start) / mean of one scalar
 * across materialised windows, plus Pearson and Spearman correlation when a second
 * scalar is given.
 *
 * <p>Exists because the panel's follow-up computations kept dying on the sandbox
 * loop cap: P-b72c-121's open questions — "quantify secular growth of
 * max_divisor_count near HCN windows" and "compute the correlation coefficient
 * between max_divisor_count and mertens_per_sqrt_W over the refuted windows" —
 * were each attempted via {@code run_sandboxed_code} and failed ("Loop limit
 * exceeded") in three separate sessions. The same numbers fall out of the
 * already-materialised {@code ConjectureVerifier.sampleWindows} maps in O(windows).
 *
 * <p>Pure, deterministic, stateless — same shape as the Wave-1/4 gates. Windows
 * missing either scalar (null/NaN) are skipped pairwise.
 */
public final class ScalarWindowStats {

    private ScalarWindowStats() {
    }

    /** Per-scalar summary over the sampled windows. */
    public record Series(String name, int n, double min, double max,
                         long argmaxWindowStart, double mean) {
    }

    /**
     * @param windows   windows actually used (pairwise-complete when rhs present)
     * @param lhs       always present
     * @param rhs       null when only one scalar was requested
     * @param pearson   null unless rhs present and n >= 3 with variance on both sides
     * @param spearman  same condition as pearson
     */
    public record Result(int windows, Series lhs, Series rhs, Double pearson, Double spearman) {
    }

    /**
     * Compute stats over {@code samples} (one map per window, as produced by
     * {@code ConjectureVerifier.sampleWindows} / {@code MathInvariants.extract}).
     * {@code windowStart}/{@code windowSize} locate window i at
     * {@code windowStart + i*windowSize} for the argmax report.
     *
     * @return null when no window carries the lhs scalar (caller renders a clear
     *         "no data" message rather than a zero-filled table)
     */
    public static Result compute(List<Map<String, Double>> samples,
                                 String lhsName, String rhsName,
                                 long windowStart, int windowSize) {
        if (samples == null || samples.isEmpty() || lhsName == null) {
            return null;
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = rhsName == null ? null : new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Map<String, Double> w = samples.get(i);
            Double x = w.get(lhsName);
            if (x == null || !Double.isFinite(x)) continue;
            if (rhsName != null) {
                Double y = w.get(rhsName);
                if (y == null || !Double.isFinite(y)) continue;   // pairwise-complete
                ys.add(y);
            }
            xs.add(x);
            starts.add(windowStart + (long) i * windowSize);
        }
        if (xs.isEmpty()) {
            return null;
        }
        Series lhs = summarize(lhsName, xs, starts);
        Series rhs = rhsName == null ? null : summarize(rhsName, ys, starts);
        Double pearson = null;
        Double spearman = null;
        if (rhsName != null && xs.size() >= 3) {
            double p = pearson(toArray(xs), toArray(ys));
            double s = pearson(ranks(xs), ranks(ys));
            pearson = Double.isFinite(p) ? p : null;
            spearman = Double.isFinite(s) ? s : null;
        }
        return new Result(xs.size(), lhs, rhs, pearson, spearman);
    }

    private static Series summarize(String name, List<Double> v, List<Long> starts) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long argmax = starts.isEmpty() ? 0 : starts.get(0);
        double sum = 0;
        for (int i = 0; i < v.size(); i++) {
            double x = v.get(i);
            sum += x;
            if (x < min) min = x;
            if (x > max) {
                max = x;
                argmax = starts.get(i);
            }
        }
        return new Series(name, v.size(), min, max, argmax, sum / v.size());
    }

    private static double[] toArray(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    /** Average-rank vector (ties share the mean rank) — Spearman = Pearson over these. */
    static double[] ranks(List<Double> v) {
        int n = v.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v.get(a), v.get(b)));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v.get(idx[j + 1]).doubleValue() == v.get(idx[i]).doubleValue()) j++;
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    /** Pearson correlation; NaN when either side is constant. */
    static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            cov += da * db; va += da * da; vb += db * db;
        }
        if (va <= 0 || vb <= 0) return Double.NaN;
        return cov / Math.sqrt(va * vb);
    }
}
