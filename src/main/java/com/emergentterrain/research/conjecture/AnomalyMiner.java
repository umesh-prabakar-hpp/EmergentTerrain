package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 30 Wave 5 (Stage-1 generation) — anomaly mining (30.1): keep the exceptions.
 *
 * <p>When a bound that held across many runs is finally broken by a counterexample, the exception is
 * not just a reason to retire it — it can be the <em>finding</em>: a regime where an otherwise-robust
 * pattern fails, worth investigating. 30.1 surfaces those as candidate anomalies.
 *
 * <p>A refuted conjecture is an anomaly candidate when it was a <b>real pattern first</b> — confirmed
 * across at least {@code minConfirmed} runs (its confirmation history is the held-out evidence that it
 * generalised before failing) — and the refutation is <b>surprising</b> relative to the bound's
 * normal slack. The surprise is scored as an exponential tail
 * {@code p = exp(−worstMargin / max(p99Slack, tol))}: a tightly-confirmed bound (small slack) broken by
 * a large margin scores a tiny p (strong anomaly); a loose bound nicked just past the threshold scores
 * a large p (ordinary). That score is treated as a p-value and corrected across the candidate family by
 * {@link MultipleComparisonsCorrection}, so only anomalies whose surprise survives the multiple-
 * comparisons correction are kept — directly the acceptance bar "surviving anomalies past held-out
 * re-check + MCC".
 *
 * <p>Pure, stateless, deterministic. The surprise score is a heuristic tail, not a calibrated p-value;
 * a live re-evaluation of the anomaly window (a true held-out re-check against the substrate) is the
 * follow-on, mirroring the residual-structure boundary. Reports candidates; persisting an anomaly as an
 * {@code UNEXPLAINED_OBSERVATION} paper is Wave 7.
 */
public final class AnomalyMiner {

    private AnomalyMiner() {
    }

    /**
     * @param lhs/rhs       the refuted relation
     * @param confirmedRuns how many runs it held before refutation (held-out evidence)
     * @param worstMargin   the largest counterexample margin (how hard it broke)
     * @param pValue        surprise tail score (smaller = more surprising)
     */
    public record Anomaly(String lhs, String rhs, int confirmedRuns, double worstMargin, double pValue) {
    }

    /**
     * @param refuted     counterexample-refuted conjectures (with their {@code refutedBy} witnesses)
     * @param minConfirmed eligibility: only conjectures confirmed at least this many runs qualify
     * @param tol         slack floor for the surprise scale (use {@link ConjectureEngine#TOL})
     * @param method      multiple-comparisons procedure
     * @param alpha       family error level
     * @return surviving anomalies (H0 "ordinary exception" rejected after correction), p ascending
     */
    public static List<Anomaly> mine(List<Inequality> refuted, int minConfirmed, double tol,
                                     MultipleComparisonsCorrection.Method method, double alpha) {
        List<Anomaly> eligible = new ArrayList<>();
        if (refuted == null) {
            return eligible;
        }
        for (Inequality c : refuted) {
            if (c == null || c.confirmedRuns() < minConfirmed
                    || c.refutedBy() == null || c.refutedBy().isEmpty()) {
                continue;
            }
            double worst = Double.NEGATIVE_INFINITY;
            for (Witness w : c.refutedBy()) {
                if (w.margin() > worst) {
                    worst = w.margin();
                }
            }
            if (!Double.isFinite(worst)) {
                continue;
            }
            double scale = Math.max(c.p99Slack(), tol);
            double p = Math.exp(-worst / scale);   // exponential surprise tail in (0,1]
            eligible.add(new Anomaly(c.lhs(), c.rhs(), c.confirmedRuns(), worst, p));
        }
        if (eligible.isEmpty()) {
            return eligible;
        }
        double[] ps = new double[eligible.size()];
        for (int i = 0; i < ps.length; i++) {
            ps[i] = eligible.get(i).pValue();
        }
        boolean[] reject = MultipleComparisonsCorrection.reject(ps, method, alpha);
        List<Anomaly> survivors = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            if (reject[i]) {
                survivors.add(eligible.get(i));
            }
        }
        survivors.sort(java.util.Comparator.comparingDouble(Anomaly::pValue)
                .thenComparing(a -> a.lhs() + "|" + a.rhs()));
        return survivors;
    }
}
