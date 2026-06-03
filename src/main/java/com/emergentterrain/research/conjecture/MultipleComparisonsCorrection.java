package com.emergentterrain.research.conjecture;

/**
 * Phase 30 Wave 4 (evaluation hardening) — multiple-comparisons correction over a family
 * of per-conjecture null p-values (from {@link ConjectureStochasticNull}).
 *
 * <p>Each mine pass tests many candidate bounds simultaneously against the no-effect null.
 * Judging each at a raw {@code p ≤ α} inflates the family-wise false-discovery rate: with
 * {@code m} independent tests at α=0.05 you expect ~0.05·m spurious "significant" bounds by
 * chance. This applies the standard corrections so a bound is only kept (its no-effect H0
 * only rejected) at a level that controls error across the whole family — the denominator
 * {@code m} being the W1-reduced surviving pool, so the correction isn't crippling.
 *
 * <ul>
 *   <li><b>Bonferroni</b> (controls FWER): reject H0_i iff {@code p_i ≤ α/m}.</li>
 *   <li><b>Benjamini–Hochberg</b> (controls FDR, step-up): with p-values sorted ascending
 *       {@code p_(1) ≤ … ≤ p_(m)}, find the largest {@code k} with {@code p_(k) ≤ (k/m)·α}
 *       and reject the {@code k} smallest. Less conservative than Bonferroni, and its
 *       rejection set is always a superset of Bonferroni's.</li>
 * </ul>
 *
 * <p>Pure, stateless, deterministic. "Reject H0" = the bound is a genuine association
 * (kept); "fail to reject" = indistinguishable from the null at the corrected level
 * (retired). The same standalone shape as the other Wave-1/4 gates.
 */
public final class MultipleComparisonsCorrection {

    private MultipleComparisonsCorrection() {
    }

    public enum Method {
        BONFERRONI,
        BENJAMINI_HOCHBERG;

        /** Parse a config string ("bonferroni" | "bh"/"benjamini_hochberg"); defaults to BH. */
        public static Method parse(String s) {
            if (s == null) {
                return BENJAMINI_HOCHBERG;
            }
            String t = s.trim().toLowerCase();
            return switch (t) {
                case "bonferroni", "bonf" -> BONFERRONI;
                default -> BENJAMINI_HOCHBERG;   // "bh", "benjamini_hochberg", anything else
            };
        }
    }

    /**
     * @param pValues per-hypothesis p-values, in caller order
     * @param method  correction procedure
     * @param alpha   target error level (FWER for Bonferroni, FDR for BH)
     * @return a boolean per input index: {@code true} = reject H0 (significant / keep),
     *         {@code false} = fail to reject (not distinguishable from the null / retire)
     */
    public static boolean[] reject(double[] pValues, Method method, double alpha) {
        int m = pValues == null ? 0 : pValues.length;
        boolean[] out = new boolean[m];
        if (m == 0) {
            return out;
        }
        double a = Math.max(0.0, Math.min(1.0, alpha));
        if (method == Method.BONFERRONI) {
            double thresh = a / m;
            for (int i = 0; i < m; i++) {
                out[i] = pValues[i] <= thresh;
            }
            return out;
        }
        // Benjamini–Hochberg step-up: sort ascending by p, keep original indices.
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(pValues[x], pValues[y]));
        int largestK = 0;   // number of smallest-p hypotheses to reject (0 = none)
        for (int rank = 1; rank <= m; rank++) {
            double crit = (rank / (double) m) * a;
            if (pValues[order[rank - 1]] <= crit) {
                largestK = rank;   // keep the LARGEST rank that satisfies the BH inequality
            }
        }
        for (int rank = 1; rank <= largestK; rank++) {
            out[order[rank - 1]] = true;
        }
        return out;
    }
}
