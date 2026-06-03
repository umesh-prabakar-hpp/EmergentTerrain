package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 30 Wave 5 (Stage-1 generation) — residual-structure discovery (30.2).
 *
 * <p>A confirmed bound {@code lhs <= k*rhs + c} captures the first-order relationship between two
 * scalars. 30.2 asks the generative question: <em>after removing what {@code rhs} explains, does the
 * residual still carry structure another scalar accounts for?</em> If so, that's a newly discovered
 * relation the primary law missed — a Stage-1 generated candidate, not a filtered one.
 *
 * <p>Procedure: fit the OLS trend {@code lhs ≈ β·rhs + α}, take residuals {@code r = lhs − (α+β·rhs)},
 * then for every other scalar {@code z} test whether {@code r} tracks {@code z} beyond the no-effect
 * null. The null test is delegated verbatim to {@link ConjectureStochasticNull#evaluate} (the W4
 * permutation null on |Spearman ρ|), so "beats the linear null" means: the residual–{@code z}
 * association is significant at {@code α} against the rhs-shuffled ensemble. The strongest such
 * {@code z} (smallest p, then largest |ρ|) is returned.
 *
 * <p>Pure, stateless, deterministic. This discovers/reports the candidate; persisting a residual
 * relation as a (multivariate) conjecture or paper is a follow-on — the 2-term conjecture schema
 * can't yet store {@code lhs ~ rhs + z}.
 */
public final class ResidualStructureMiner {

    /** Below this paired-sample count neither the OLS fit nor the null test is trustworthy. */
    static final int MIN_SAMPLES = ConjectureStochasticNull.MIN_SAMPLES;

    private ResidualStructureMiner() {
    }

    /**
     * @param explainer       the scalar that explains the residual structure
     * @param residualAbsRho  |Spearman ρ| between the rhs-residual and the explainer
     * @param pValue          permutation-null p-value (≤ alpha ⇒ beats the linear null)
     */
    public record Candidate(String explainer, double residualAbsRho, double pValue) {
    }

    /**
     * @param explainers candidate explaining scalars (lhs and rhs are skipped automatically)
     * @param permutations / alpha / seed forwarded to the W4 permutation null
     * @return the strongest residual-structure candidate that beats the null, or empty
     */
    public static Optional<Candidate> mine(List<Map<String, Double>> samples, String lhs, String rhs,
                                           Collection<String> explainers, int permutations,
                                           double alpha, long seed) {
        if (samples == null || lhs == null || rhs == null || explainers == null) {
            return Optional.empty();
        }
        // Collect paired (lhs, rhs) samples and keep the source rows for residual pairing.
        List<Double> ls = new ArrayList<>();
        List<Double> rs = new ArrayList<>();
        List<Map<String, Double>> rows = new ArrayList<>();
        for (Map<String, Double> s : samples) {
            Double l = s.get(lhs);
            Double r = s.get(rhs);
            if (l != null && r != null && Double.isFinite(l) && Double.isFinite(r)) {
                ls.add(l);
                rs.add(r);
                rows.add(s);
            }
        }
        int n = ls.size();
        if (n < MIN_SAMPLES) {
            return Optional.empty();
        }
        // OLS lhs on rhs.
        double meanL = mean(ls);
        double meanR = mean(rs);
        double sRR = 0;
        double sRL = 0;
        for (int i = 0; i < n; i++) {
            double dr = rs.get(i) - meanR;
            sRR += dr * dr;
            sRL += dr * (ls.get(i) - meanL);
        }
        if (sRR <= 0.0) {
            return Optional.empty();   // rhs degenerate — no trend to remove
        }
        double beta = sRL / sRR;
        double alphaInt = meanL - beta * meanR;
        double[] residual = new double[n];
        for (int i = 0; i < n; i++) {
            residual[i] = ls.get(i) - (alphaInt + beta * rs.get(i));
        }

        Candidate best = null;
        for (String z : explainers) {
            if (z == null || z.equals(lhs) || z.equals(rhs)) {
                continue;
            }
            // Synthesise paired (residual, z) samples for the rows where z is present and finite.
            List<Map<String, Double>> synth = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Double zv = rows.get(i).get(z);
                if (zv != null && Double.isFinite(zv)) {
                    Map<String, Double> m = new LinkedHashMap<>(2);
                    m.put("__resid", residual[i]);
                    m.put("__z", zv);
                    synth.add(m);
                }
            }
            // Delegate the no-effect null to the W4 permutation null (minCv=0 — residual/z may be
            // small-variance and that's fine here). "ok" ⇒ the association beats the null at alpha.
            ConjectureStochasticNull.Result nr = ConjectureStochasticNull.evaluate(
                    synth, "__resid", "__z", permutations, alpha, 0.0, seed);
            if (!"ok".equals(nr.reason())) {
                continue;
            }
            if (best == null || nr.pValue() < best.pValue()
                    || (nr.pValue() == best.pValue() && nr.observedAbsRho() > best.residualAbsRho())) {
                best = new Candidate(z, nr.observedAbsRho(), nr.pValue());
            }
        }
        return Optional.ofNullable(best);
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }
}
