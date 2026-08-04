package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 4 — unit tests for {@link ConjectureReplicationValidator} (K-fold CV stability).
 */
class ConjectureReplicationValidatorTest {

    private static final double TOL = ConjectureEngine.TOL;   // fold held when worst margin ≤ 0.04

    private static List<Map<String, Double>> samples(double[] lhs, double[] rhs) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 0; i < lhs.length; i++) {
            out.add(Map.of("lhs", lhs[i], "rhs", rhs[i]));
        }
        return out;
    }

    @Test
    void stableBoundHoldsEveryFold() {
        // lhs = 0.5*rhs with the conjecture's slope k=0.5 → refit intercept c=0 on every fold,
        // margin 0 out-of-fold → holds every fold.
        int n = 20;
        double[] rhs = new double[n];
        double[] lhs = new double[n];
        for (int i = 0; i < n; i++) {
            rhs[i] = i + 1;
            lhs[i] = 0.5 * rhs[i];
        }
        var r = ConjectureReplicationValidator.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 0.5, 5, TOL, 0.8, 42L);
        assertThat(r.folds()).isEqualTo(5);
        assertThat(r.passRate()).isCloseTo(1.0, within(1e-9));
        assertThat(r.flagged()).isFalse();
    }

    @Test
    void fragileInterceptFailsOutOfFold() {
        // A pure bound (k=0) whose intercept is set by a lone spike: the fold CONTAINING the spike
        // refits c on spike-free folds (c≈0) and the spike then blows past it → exactly one of 4
        // folds fails → passRate 0.75 < 0.8 → flagged.
        double[] rhs = {1, 1, 1, 1, 1, 1, 1, 1};
        double[] lhs = {0, 0, 0, 0, 50, 0, 0, 0};   // lone spike; rhs carries no signal
        var r = ConjectureReplicationValidator.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 0.0, 4, TOL, 0.8, 7L);
        assertThat(r.flagged()).isTrue();
        assertThat(r.reason()).isEqualTo("replication_fragile");
        assertThat(r.passRate()).isLessThan(0.8);
    }

    @Test
    void tooFewSamplesNotFlagged() {
        // 3 samples, 5 folds → fewer than MIN_PER_FOLD per fold → can't cross-validate → keep.
        var r = ConjectureReplicationValidator.evaluate(
                samples(new double[]{1, 2, 3}, new double[]{1, 2, 3}), "lhs", "rhs", 1.0, 5, TOL, 0.8, 1L);
        assertThat(r.flagged()).isFalse();
        assertThat(r.reason()).isEqualTo("insufficient_samples");
    }

    @Test
    void foldCountReducedToFitSampleCount() {
        // 6 samples, MIN_PER_FOLD=2 → at most 3 folds even if 5 requested.
        int n = 6;
        double[] rhs = new double[n];
        double[] lhs = new double[n];
        for (int i = 0; i < n; i++) {
            rhs[i] = i + 1;
            lhs[i] = rhs[i];
        }
        var r = ConjectureReplicationValidator.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 1.0, 5, TOL, 0.8, 5L);
        assertThat(r.folds()).isLessThanOrEqualTo(3);
        assertThat(r.folds()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void deterministicForAGivenSeed() {
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        double[] lhs = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8};
        var a = ConjectureReplicationValidator.evaluate(samples(lhs, rhs), "lhs", "rhs", 1.0, 4, TOL, 0.8, 99L);
        var b = ConjectureReplicationValidator.evaluate(samples(lhs, rhs), "lhs", "rhs", 1.0, 4, TOL, 0.8, 99L);
        assertThat(a.passRate()).isEqualTo(b.passRate());
        assertThat(a.flagged()).isEqualTo(b.flagged());
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(ConjectureReplicationValidator.evaluate(null, "lhs", "rhs", 1.0, 5, TOL, 0.8, 0L).flagged())
                .isFalse();
    }
}
