package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 5 — 30.5 P2: the MDL admission ledger for a candidate scalar from
 * {@link OperatorGrammar}. Prices a candidate by minimum description length:
 *
 * <pre>  net_gain_bits = compression_bits(z) − L(expr)  &gt; 0   ⇒  admit</pre>
 *
 * Per the settled design (decisions M1/M2/M3 in {@code vocabulary-expansion-mdl.md}):
 * <ul>
 *   <li><b>Compression (M1)</b> — residual-variance → bits. For a confirmed bound's residual
 *       (lhs after the OLS rhs-trend), fit {@code residual ≈ a + b·z} and measure the
 *       out-of-sample variance reduction it buys: {@code 0.5·n·log2(var_before/var_after)}.</li>
 *   <li><b>Incremental over existing</b> — z only earns the compression an <em>existing</em>
 *       scalar doesn't already provide on the same residual (the "no existing scalar does it"
 *       clause); the best existing explainer's bits are subtracted.</li>
 *   <li><b>Held-out confirmation (M3)</b> — the {@code (a,b)} fit is estimated on a fit fold and the
 *       variance reduction is measured on a held-out fold (interleaved split), so an in-sample-only
 *       fluke earns ~0 bits.</li>
 *   <li><b>Significance</b> — z's residual association must beat the W4 permutation null
 *       ({@link ConjectureStochasticNull}).</li>
 *   <li><b>L(expr) (M2)</b> — uniform per-node code length: {@code nodeCount · log2(grammarSymbols)}.</li>
 * </ul>
 *
 * <p>Pure, stateless, deterministic. Scores ONE candidate against ONE target residual; iterating over
 * confirmed bounds, the family multiple-comparisons correction, and persisting admitted scalars are P3.
 * No {@link MathInvariants} mutation here.
 */
public final class MdlGate {

    /** Need at least this many paired samples (≥4 per interleaved fold for a 2-parameter OLS). */
    static final int MIN_SAMPLES = 8;

    private MdlGate() {
    }

    /** Tuning for the ledger. {@code grammarSymbols} = vocabulary size + operator count (for L). */
    public record Params(int grammarSymbols, int permutations, double alpha, long seed) {
    }

    /**
     * @param descriptionBits      L(expr) = nodeCount · log2(grammarSymbols)
     * @param compressionBits      z's held-out variance-reduction bits on the residual
     * @param bestExistingBits     best held-out bits any existing scalar achieves on the same residual
     * @param netGainBits          (compressionBits − bestExistingBits) − descriptionBits
     * @param beatsNull            z's residual association beats the permutation null
     * @param admit                beatsNull AND incremental > 0 AND netGainBits > 0
     */
    public record Verdict(double descriptionBits, double compressionBits, double bestExistingBits,
                          double netGainBits, boolean beatsNull, boolean admit, String reason) {
    }

    public static Verdict evaluate(OperatorGrammar.Expr z, List<Map<String, Double>> samples,
                                   String lhs, String rhs, Collection<String> existingScalars, Params p) {
        double lExpr = z == null ? 0 : z.nodeCount() * (Math.log(Math.max(2, p.grammarSymbols())) / Math.log(2));
        if (z == null || samples == null || lhs == null || rhs == null) {
            return new Verdict(lExpr, 0, 0, -lExpr, false, false, "insufficient_samples");
        }
        // Primary residual: lhs after the OLS rhs-trend, over samples where lhs & rhs are finite.
        List<Double> ls = new ArrayList<>();
        List<Double> rsv = new ArrayList<>();
        List<Map<String, Double>> rows = new ArrayList<>();
        for (Map<String, Double> s : samples) {
            Double l = s.get(lhs);
            Double r = s.get(rhs);
            if (finite(l) && finite(r)) {
                ls.add(l);
                rsv.add(r);
                rows.add(s);
            }
        }
        if (ls.size() < MIN_SAMPLES) {
            return new Verdict(lExpr, 0, 0, -lExpr, false, false, "insufficient_samples");
        }
        double[] base = olsFit(toArr(rsv), toArr(ls));
        if (base == null) {
            return new Verdict(lExpr, 0, 0, -lExpr, false, false, "rhs_degenerate");
        }
        int n = ls.size();
        double[] residual = new double[n];
        for (int i = 0; i < n; i++) {
            residual[i] = ls.get(i) - (base[0] + base[1] * rsv.get(i));
        }

        // z's held-out compression on the residual.
        double zBits = heldOutCompressionBits(residual, evalSeries(z, rows));
        // Significance vs the permutation null.
        boolean beatsNull = beatsNull(residual, evalSeries(z, rows), p);
        // Best existing scalar's held-out compression on the same residual.
        double bestExisting = 0.0;
        if (existingScalars != null) {
            for (String e : existingScalars) {
                if (e == null || e.equals(lhs) || e.equals(rhs)) {
                    continue;
                }
                bestExisting = Math.max(bestExisting, heldOutCompressionBits(residual, namedSeries(e, rows)));
            }
        }
        double incremental = zBits - bestExisting;
        double netGain = incremental - lExpr;

        String reason;
        boolean admit;
        if (!beatsNull) {
            reason = "fails_null";
            admit = false;
        } else if (zBits <= 0) {
            reason = "no_compression";
            admit = false;
        } else if (incremental <= 0) {
            reason = "explained_by_existing";
            admit = false;
        } else if (netGain <= 0) {
            reason = "below_description_cost";
            admit = false;
        } else {
            reason = "admit";
            admit = true;
        }
        return new Verdict(lExpr, zBits, bestExisting, netGain, beatsNull, admit, reason);
    }

    // ---- helpers ----

    private static double[] evalSeries(OperatorGrammar.Expr z, List<Map<String, Double>> rows) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = z.eval(rows.get(i));
        }
        return out;
    }

    private static double[] namedSeries(String name, List<Map<String, Double>> rows) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) {
            Double v = rows.get(i).get(name);
            out[i] = finite(v) ? v : Double.NaN;
        }
        return out;
    }

    /**
     * Out-of-sample variance reduction (bits) from explaining {@code residual} with {@code predictor}:
     * fit {@code a+b·predictor} on the interleaved fit fold, measure the reduced variance on the held
     * fold. Pairs with a non-finite predictor are dropped. 0 if no usable improvement.
     */
    private static double heldOutCompressionBits(double[] residual, double[] predictor) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < residual.length; i++) {
            if (finite(residual[i]) && finite(predictor[i])) {
                pairs.add(new double[]{predictor[i], residual[i]});   // {x, y}
            }
        }
        if (pairs.size() < MIN_SAMPLES) {
            return 0.0;
        }
        List<Double> fx = new ArrayList<>();
        List<Double> fy = new ArrayList<>();
        List<Double> hx = new ArrayList<>();
        List<Double> hy = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            (i % 2 == 0 ? fx : hx).add(pairs.get(i)[0]);
            (i % 2 == 0 ? fy : hy).add(pairs.get(i)[1]);
        }
        if (fx.size() < 4 || hx.size() < 4) {
            return 0.0;
        }
        double[] fit = olsFit(toArr(fx), toArr(fy));
        if (fit == null) {
            return 0.0;   // predictor degenerate on the fit fold
        }
        double[] yh = toArr(hy);
        double[] xh = toArr(hx);
        double varBefore = variance(yh);
        if (varBefore <= 0) {
            return 0.0;
        }
        double[] resid = new double[yh.length];
        for (int i = 0; i < yh.length; i++) {
            resid[i] = yh[i] - (fit[0] + fit[1] * xh[i]);
        }
        double varAfter = Math.max(variance(resid), varBefore * 1e-6);   // floor avoids ∞ bits
        if (varAfter >= varBefore) {
            return 0.0;
        }
        return 0.5 * yh.length * (Math.log(varBefore / varAfter) / Math.log(2));
    }

    private static boolean beatsNull(double[] residual, double[] predictor, Params p) {
        List<Map<String, Double>> synth = new ArrayList<>();
        for (int i = 0; i < residual.length; i++) {
            if (finite(residual[i]) && finite(predictor[i])) {
                Map<String, Double> m = new LinkedHashMap<>(2);
                m.put("__r", residual[i]);
                m.put("__z", predictor[i]);
                synth.add(m);
            }
        }
        return "ok".equals(ConjectureStochasticNull.evaluate(
                synth, "__r", "__z", p.permutations(), p.alpha(), 0.0, p.seed()).reason());
    }

    /** {a, b} for y ≈ a + b·x, or null if x has no variance. */
    private static double[] olsFit(double[] x, double[] y) {
        int n = x.length;
        double mx = mean(x);
        double my = mean(y);
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            sxx += dx * dx;
            sxy += dx * (y[i] - my);
        }
        if (sxx <= 0) {
            return null;
        }
        double b = sxy / sxx;
        return new double[]{my - b * mx, b};
    }

    private static double variance(double[] v) {
        if (v.length == 0) {
            return 0;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return s / v.length;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static double[] toArr(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i);
        }
        return a;
    }

    private static boolean finite(Double d) {
        return d != null && Double.isFinite(d);
    }
}
