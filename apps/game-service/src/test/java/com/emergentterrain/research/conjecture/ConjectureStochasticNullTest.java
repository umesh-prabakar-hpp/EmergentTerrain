package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 4 — unit tests for {@link ConjectureStochasticNull}, the generative
 * stochastic null for {@code lhs <= k*rhs + c}. Pure: no DB, sidecar, or Spring.
 */
class ConjectureStochasticNullTest {

    private static final int PERMS = 999;
    private static final double ALPHA = 0.05;
    private static final double MIN_CV = 0.01;

    /** Build samples from explicit lhs/rhs value pairs. */
    private static List<Map<String, Double>> samples(double[] lhs, double[] rhs) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 0; i < lhs.length; i++) {
            out.add(Map.of("lhs", lhs[i], "rhs", rhs[i]));
        }
        return out;
    }

    @Test
    void strongRealAssociationBeatsNull() {
        // lhs monotonically tracks rhs → |Spearman ρ| = 1 → essentially no permutation
        // reaches it → low p → NOT flagged (a genuine relation survives).
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] lhs = {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};
        ConjectureStochasticNull.Result r = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 42L);
        assertThat(r.flagged()).isFalse();
        assertThat(r.observedAbsRho()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.pValue()).isLessThan(ALPHA);
        assertThat(r.reason()).isEqualTo("ok");
    }

    @Test
    void spuriousUncorrelatedPairIsFlagged() {
        // lhs and rhs unrelated (rhs varies enough to pass the CV floor, but no rank
        // association) → |ρ|≈0 → most permutations match or beat it → high p → flagged.
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] lhs = {5, 5, 5, 6, 5, 6, 5, 6, 5, 6};   // no monotone tracking of rhs
        ConjectureStochasticNull.Result r = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 7L);
        assertThat(r.flagged()).isTrue();
        assertThat(r.reason()).isEqualTo("null_not_beaten");
        assertThat(r.pValue()).isGreaterThan(ALPHA);
    }

    @Test
    void nearConstantRhsIsFlaggedByCvFloor() {
        // rhs ≈ constant → k*rhs is a constant → degenerate bound, flagged regardless of p.
        double[] rhs = {5.000, 5.001, 5.000, 5.001, 5.000, 5.001, 5.000, 5.001};
        double[] lhs = {1, 2, 3, 4, 5, 6, 7, 8};
        ConjectureStochasticNull.Result r = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 1L);
        assertThat(r.flagged()).isTrue();
        assertThat(r.reason()).isEqualTo("rhs_near_constant_cv");
        assertThat(r.rhsCv()).isLessThan(MIN_CV);
    }

    @Test
    void tooFewSamplesNeverFlags() {
        // Below MIN_SAMPLES the permutation test has no power — keep, never retire.
        double[] rhs = {1, 2, 3};
        double[] lhs = {9, 1, 5};
        ConjectureStochasticNull.Result r = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 3L);
        assertThat(r.flagged()).isFalse();
        assertThat(r.reason()).isEqualTo("insufficient_samples");
    }

    @Test
    void deterministicForAGivenSeed() {
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] lhs = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3};
        ConjectureStochasticNull.Result a = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 99L);
        ConjectureStochasticNull.Result b = ConjectureStochasticNull.evaluate(
                samples(lhs, rhs), "lhs", "rhs", PERMS, ALPHA, MIN_CV, 99L);
        assertThat(a.pValue()).isEqualTo(b.pValue());
        assertThat(a.flagged()).isEqualTo(b.flagged());
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(ConjectureStochasticNull.evaluate(null, "lhs", "rhs", PERMS, ALPHA, MIN_CV, 0L)
                .flagged()).isFalse();
        assertThat(ConjectureStochasticNull.evaluate(samples(new double[]{1}, new double[]{1}),
                null, "rhs", PERMS, ALPHA, MIN_CV, 0L).reason()).isEqualTo("insufficient_samples");
    }
}
