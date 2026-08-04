package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.emergentterrain.research.conjecture.MultipleComparisonsCorrection.Method;

/**
 * Phase 30 Wave 5 — unit tests for {@link AnomalyMiner} (30.1 anomaly mining).
 */
class AnomalyMinerTest {

    private static final double TOL = ConjectureEngine.TOL;   // 0.02
    private static final int MIN_CONFIRMED = 5;

    /** A refuted conjecture: confirmed N runs, slack p99, broken by the given counterexample margins. */
    private static Inequality refuted(String lhs, int confirmed, double p99, double... margins) {
        Inequality i = new Inequality(lhs, "rhs", 1.0, 0.0);
        i.confirmedRuns = confirmed;        // package-private fields, set directly in-package
        i.p99Slack = p99;
        for (double m : margins) {
            i.refutedBy.add(new Witness("run", 0L, m));
        }
        return i;
    }

    @Test
    void strongSurpriseSurvivesAndWeakIsFiltered() {
        // strong: confirmed 50, very tight (p99=0.005), broke by 0.5 → p≈exp(-25)≈1e-11.
        // weak: confirmed 50, loose (p99=0.04), nicked just past the threshold by 0.05 → p≈0.29.
        Inequality strong = refuted("strong", 50, 0.005, 0.5);
        Inequality weak = refuted("weak", 50, 0.04, 0.05);
        List<AnomalyMiner.Anomaly> res =
                AnomalyMiner.mine(List.of(strong, weak), MIN_CONFIRMED, TOL, Method.BENJAMINI_HOCHBERG, 0.05);
        assertThat(res).extracting(AnomalyMiner.Anomaly::lhs).containsExactly("strong");
    }

    @Test
    void notConfirmedEnoughIsExcluded() {
        // Broke hard, but it was never a real pattern (confirmed 2 < 5) → not an anomaly.
        Inequality flaky = refuted("flaky", 2, 0.005, 0.9);
        assertThat(AnomalyMiner.mine(List.of(flaky), MIN_CONFIRMED, TOL, Method.BENJAMINI_HOCHBERG, 0.05))
                .isEmpty();
    }

    @Test
    void noWitnessIsExcluded() {
        Inequality noWit = refuted("nowit", 50, 0.005);   // no counterexample margins
        assertThat(AnomalyMiner.mine(List.of(noWit), MIN_CONFIRMED, TOL, Method.BENJAMINI_HOCHBERG, 0.05))
                .isEmpty();
    }

    @Test
    void worstMarginAcrossWitnessesIsUsed() {
        Inequality multi = refuted("multi", 50, 0.005, 0.1, 0.6, 0.2);
        var res = AnomalyMiner.mine(List.of(multi), MIN_CONFIRMED, TOL, Method.BENJAMINI_HOCHBERG, 0.05);
        assertThat(res).hasSize(1);
        assertThat(res.get(0).worstMargin()).isEqualTo(0.6);
    }

    @Test
    void emptyAndNullSafe() {
        assertThat(AnomalyMiner.mine(List.of(), MIN_CONFIRMED, TOL, Method.BONFERRONI, 0.05)).isEmpty();
        assertThat(AnomalyMiner.mine(null, MIN_CONFIRMED, TOL, Method.BONFERRONI, 0.05)).isEmpty();
    }
}
