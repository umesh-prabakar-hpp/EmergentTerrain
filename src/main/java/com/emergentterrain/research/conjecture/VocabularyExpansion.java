package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 5 — 30.5 P3 admission orchestration (pure). Given grammar candidates
 * ({@link OperatorGrammar}), the window samples, and a set of target residuals (confirmed bounds),
 * admits the candidates that earn their keep under {@link MdlGate}, ranked by net MDL gain and
 * capped by a budget.
 *
 * <p>Each candidate is scored against every target {@code (lhs, rhs)} residual; it is admitted if it
 * is admitted for at least one target (it explained <em>some</em> residual beyond the null, the
 * existing scalars, and its description cost), keeping its best net-gain. The per-candidate null seed
 * is derived from the candidate render so the verdict is deterministic and candidate-independent.
 *
 * <p>Pure, stateless, deterministic. The budget (typically {@link SublinearGenerationBudget}) bounds
 * how many scalars enter the vocabulary per pass. Persistence (P3b) and the
 * {@code materialize}/seeding wiring shipped 2026-06-05 — see {@code ConjectureMinerService} (the loop
 * persists admitted scalars to {@code derived_scalars} and seeds conjectures that touch them). What
 * remains open is *admission itself staying at ~0 in practice*; {@link #admitWithReport} surfaces the
 * per-candidate MDL rejection reason so a mine pass can tell whether the residuals are already explained
 * by existing vocabulary (correctly inert) or the grammar/calibration is the limiter — see
 * {@code novelty-ceiling-and-vocab-activation.md} Stage A.
 */
public final class VocabularyExpansion {

    private VocabularyExpansion() {
    }

    /** A target residual to explain: the bound {@code lhs <= k*rhs + c}. */
    public record Target(String lhs, String rhs) {
    }

    /**
     * The admitted scalars plus a histogram of the per-candidate MDL outcome — one entry per probed
     * candidate, keyed by {@link MdlGate.Verdict#reason()} ({@code admit}, {@code explained_by_existing},
     * {@code below_description_cost}, {@code fails_null}, {@code no_compression}). The "admit" count is
     * the number that passed MDL and may exceed the budget-capped {@link #admitted()} size.
     */
    public record AdmissionReport(List<DerivedScalar> admitted, Map<String, Integer> reasonCounts,
                                  double closestBelowCostNetGain, String closestBelowCostCandidate) {
        /** Histogram-only convenience (no below-cost detail). */
        public AdmissionReport(List<DerivedScalar> admitted, Map<String, Integer> reasonCounts) {
            this(admitted, reasonCounts, Double.NEGATIVE_INFINITY, null);
        }
    }

    public static List<DerivedScalar> admit(List<OperatorGrammar.Expr> candidates,
                                            List<Map<String, Double>> samples, List<Target> targets,
                                            Collection<String> existing, int budget,
                                            MdlGate.Params baseParams) {
        return admitWithReport(candidates, samples, targets, existing, budget, baseParams).admitted();
    }

    /**
     * Stage A diagnostic admission: identical admit decision as {@link #admit}, but also returns the
     * per-candidate rejection-reason histogram. For each candidate the reported reason is its BEST shot
     * across all targets (the verdict with the highest net gain) — so {@code explained_by_existing}
     * dominating means existing vocabulary already covers the residuals (correctly inert), whereas
     * {@code below_description_cost} with real compression points at a grammar-depth / calibration limit.
     */
    public static AdmissionReport admitWithReport(List<OperatorGrammar.Expr> candidates,
                                                  List<Map<String, Double>> samples, List<Target> targets,
                                                  Collection<String> existing, int budget,
                                                  MdlGate.Params baseParams) {
        List<DerivedScalar> out = new ArrayList<>();
        Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        if (budget <= 0 || candidates == null || candidates.isEmpty()
                || samples == null || targets == null || targets.isEmpty()) {
            return new AdmissionReport(out, reasons);
        }
        List<DerivedScalar> admitted = new ArrayList<>();
        // Stage C observability — the below_description_cost candidate CLOSEST to admission (its netGain
        // is the largest, i.e. least negative). "X bits short" tells whether the gate is nearly crossing
        // (a calibration tweak could help) or far off (the candidates genuinely aren't worth a scalar).
        double closestBelowCostGain = Double.NEGATIVE_INFINITY;
        String closestBelowCostCand = null;
        for (OperatorGrammar.Expr cand : candidates) {
            MdlGate.Params p = new MdlGate.Params(baseParams.grammarSymbols(), baseParams.permutations(),
                    baseParams.alpha(), baseParams.seed() ^ cand.render().hashCode(),
                    baseParams.typeAwareDescriptionCost());
            double bestGain = Double.NEGATIVE_INFINITY;
            boolean ok = false;
            String bestReason = null;
            double bestReasonGain = Double.NEGATIVE_INFINITY;
            for (Target t : targets) {
                MdlGate.Verdict v = MdlGate.evaluate(cand, samples, t.lhs(), t.rhs(), existing, p);
                if (bestReason == null || v.netGainBits() > bestReasonGain) {
                    bestReasonGain = v.netGainBits();
                    bestReason = v.reason();
                }
                if (v.admit() && v.netGainBits() > bestGain) {
                    bestGain = v.netGainBits();
                    ok = true;
                }
            }
            reasons.merge(ok ? "admit" : bestReason, 1, Integer::sum);
            if (ok) {
                admitted.add(new DerivedScalar(cand.render(), cand, bestGain));
            } else if ("below_description_cost".equals(bestReason) && bestReasonGain > closestBelowCostGain) {
                closestBelowCostGain = bestReasonGain;
                closestBelowCostCand = cand.render();
            }
        }
        admitted.sort(Comparator.<DerivedScalar>comparingDouble(d -> -d.gainBits())
                .thenComparing(DerivedScalar::name));
        for (DerivedScalar d : admitted) {
            if (out.size() >= budget) {
                break;
            }
            out.add(d);
        }
        return new AdmissionReport(out, reasons, closestBelowCostGain, closestBelowCostCand);
    }
}
