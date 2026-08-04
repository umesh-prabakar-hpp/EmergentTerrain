package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 2026-06-05 — pure stats behind the scalar_window_stats tool. Pins the
 * Pearson/Spearman math on known values, pairwise-complete filtering, the
 * argmax window report, and the null contract on empty/absent data.
 */
class ScalarWindowStatsTest {

    private static Map<String, Double> w(double a, double b) {
        return Map.of("alpha", a, "beta", b);
    }

    @Test
    void perfectlyLinearPairGivesPearsonAndSpearmanOne() {
        List<Map<String, Double>> samples = List.of(w(1, 2), w(2, 4), w(3, 6), w(4, 8));
        ScalarWindowStats.Result r = ScalarWindowStats.compute(samples, "alpha", "beta", 1000, 500);
        assertThat(r.windows()).isEqualTo(4);
        assertThat(r.pearson()).isCloseTo(1.0, within(1e-9));
        assertThat(r.spearman()).isCloseTo(1.0, within(1e-9));
        assertThat(r.lhs().mean()).isCloseTo(2.5, within(1e-9));
        assertThat(r.rhs().max()).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void monotoneNonlinearPairSeparatesSpearmanFromPearson() {
        // y = x^3 — monotone (Spearman 1) but not linear (Pearson < 1).
        List<Map<String, Double>> samples = List.of(
                w(1, 1), w(2, 8), w(3, 27), w(4, 64), w(5, 125));
        ScalarWindowStats.Result r = ScalarWindowStats.compute(samples, "alpha", "beta", 1, 100);
        assertThat(r.spearman()).isCloseTo(1.0, within(1e-9));
        assertThat(r.pearson()).isLessThan(0.99);
        assertThat(r.pearson()).isGreaterThan(0.8);
    }

    @Test
    void argmaxReportsTheAchievingWindowStart() {
        // windows at 1000, 1500, 2000 — max alpha (9) in the middle window.
        List<Map<String, Double>> samples = List.of(w(3, 0), w(9, 0), w(5, 0));
        ScalarWindowStats.Result r = ScalarWindowStats.compute(samples, "alpha", null, 1000, 500);
        assertThat(r.lhs().argmaxWindowStart()).isEqualTo(1500);
        assertThat(r.rhs()).isNull();
        assertThat(r.pearson()).isNull();
    }

    @Test
    void pairwiseIncompleteWindowsAreSkipped() {
        List<Map<String, Double>> samples = List.of(
                w(1, 2), Map.of("alpha", 5.0), w(3, 6));   // middle window lacks beta
        ScalarWindowStats.Result r = ScalarWindowStats.compute(samples, "alpha", "beta", 100, 100);
        assertThat(r.windows()).isEqualTo(2);
    }

    @Test
    void constantSideYieldsNullCorrelationNotNan() {
        List<Map<String, Double>> samples = List.of(w(1, 7), w(2, 7), w(3, 7));
        ScalarWindowStats.Result r = ScalarWindowStats.compute(samples, "alpha", "beta", 1, 100);
        assertThat(r.pearson()).isNull();
        assertThat(r.spearman()).isNull();
    }

    @Test
    void absentScalarReturnsNull() {
        List<Map<String, Double>> samples = List.of(Map.of("other", 1.0));
        assertThat(ScalarWindowStats.compute(samples, "alpha", null, 1, 100)).isNull();
        assertThat(ScalarWindowStats.compute(List.of(), "alpha", null, 1, 100)).isNull();
    }
}
