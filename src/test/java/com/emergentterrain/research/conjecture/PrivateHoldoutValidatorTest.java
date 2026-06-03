package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 4 — unit tests for {@link PrivateHoldoutValidator}, the anti-HARKing
 * private held-out gate. Uses the same refute threshold as the engine ({@code 2*TOL}).
 */
class PrivateHoldoutValidatorTest {

    private static final double TOL = ConjectureEngine.TOL;   // 0.02 → fail at margin > 0.04

    private static List<Map<String, Double>> samples(double[] lhs, double[] rhs) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 0; i < lhs.length; i++) {
            out.add(Map.of("lhs", lhs[i], "rhs", rhs[i]));
        }
        return out;
    }

    @Test
    void boundThatGeneralisesPassesHoldout() {
        // lhs <= 1*rhs + 0 holds comfortably on the private windows → not gamed.
        var v = PrivateHoldoutValidator.evaluate("lhs", "rhs", 1.0, 0.0,
                samples(new double[]{1, 2, 3, 4}, new double[]{2, 3, 4, 5}), TOL);
        assertThat(v.failedHoldout()).isFalse();
        assertThat(v.samplesEvaluated()).isEqualTo(4);
        assertThat(v.worstMargin()).isLessThanOrEqualTo(2 * TOL);
    }

    @Test
    void overfitBoundFailsHoldout() {
        // lhs <= 1*rhs + 0 was tuned on public windows but a private sample violates it badly
        // (lhs=5 > rhs=2) → worst margin 3.0 ≫ 2*TOL → passed public, failed private.
        var v = PrivateHoldoutValidator.evaluate("lhs", "rhs", 1.0, 0.0,
                samples(new double[]{1, 2, 5}, new double[]{2, 3, 2}), TOL);
        assertThat(v.failedHoldout()).isTrue();
        assertThat(v.worstMargin()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void smallMarginWithinTwoTolStillPasses() {
        // worst margin 0.03 < 2*TOL (0.04) → a bound with a little slack is not flagged.
        var v = PrivateHoldoutValidator.evaluate("lhs", "rhs", 1.0, 0.0,
                samples(new double[]{2.03}, new double[]{2.0}), TOL);
        assertThat(v.worstMargin()).isCloseTo(0.03, within(1e-9));
        assertThat(v.failedHoldout()).isFalse();
    }

    @Test
    void noPrivateEvidenceDoesNotAccuse() {
        // rhs scalar absent on the private windows → samplesEvaluated 0, never flagged.
        List<Map<String, Double>> noRhs = List.of(Map.of("lhs", 1.0), Map.of("lhs", 2.0));
        var v = PrivateHoldoutValidator.evaluate("lhs", "rhs", 1.0, 0.0, noRhs, TOL);
        assertThat(v.samplesEvaluated()).isZero();
        assertThat(v.failedHoldout()).isFalse();
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(PrivateHoldoutValidator.evaluate("lhs", "rhs", 1, 0, null, TOL).failedHoldout())
                .isFalse();
    }

    @Test
    void gamingRateArithmetic() {
        assertThat(PrivateHoldoutValidator.gamingRate(0, 0)).isEqualTo(0.0);
        assertThat(PrivateHoldoutValidator.gamingRate(10, 3)).isCloseTo(0.3, within(1e-9));
        assertThat(PrivateHoldoutValidator.gamingRate(4, 4)).isEqualTo(1.0);
    }
}
