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
 * how many scalars enter the vocabulary per pass. This returns the admitted set; persisting it and
 * wiring it into {@code materialize}/seeding so new conjectures are produced and re-evaluable across
 * passes is P3b (needs persistence).
 */
public final class VocabularyExpansion {

    private VocabularyExpansion() {
    }

    /** A target residual to explain: the bound {@code lhs <= k*rhs + c}. */
    public record Target(String lhs, String rhs) {
    }

    public static List<DerivedScalar> admit(List<OperatorGrammar.Expr> candidates,
                                            List<Map<String, Double>> samples, List<Target> targets,
                                            Collection<String> existing, int budget,
                                            MdlGate.Params baseParams) {
        List<DerivedScalar> out = new ArrayList<>();
        if (budget <= 0 || candidates == null || candidates.isEmpty()
                || samples == null || targets == null || targets.isEmpty()) {
            return out;
        }
        List<DerivedScalar> admitted = new ArrayList<>();
        for (OperatorGrammar.Expr cand : candidates) {
            MdlGate.Params p = new MdlGate.Params(baseParams.grammarSymbols(), baseParams.permutations(),
                    baseParams.alpha(), baseParams.seed() ^ cand.render().hashCode());
            double bestGain = Double.NEGATIVE_INFINITY;
            boolean ok = false;
            for (Target t : targets) {
                MdlGate.Verdict v = MdlGate.evaluate(cand, samples, t.lhs(), t.rhs(), existing, p);
                if (v.admit() && v.netGainBits() > bestGain) {
                    bestGain = v.netGainBits();
                    ok = true;
                }
            }
            if (ok) {
                admitted.add(new DerivedScalar(cand.render(), cand, bestGain));
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
        return out;
    }
}
