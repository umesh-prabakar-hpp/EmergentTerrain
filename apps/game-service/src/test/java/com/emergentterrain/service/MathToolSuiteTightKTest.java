package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * 2026-06-11 (rec 2) — the tightest-holding-k computation verify_conjecture suggests when a bound is
 * loose, so the professor tightens to the empirical fit (e.g. ~0.5·log²x for max_prime_gap) instead of a
 * textbook asymptotic constant (Cramér's k=1).
 */
class MathToolSuiteTightKTest {

    @Test
    void tightestK_isMaxRatioAcrossWindows() {
        // max_prime_gap ~ 0.5·log²x at the worst window: gaps {50,100,72} over log² {100,200,207}.
        double[] lhs = {50, 100, 72};
        double[] rhs = {100, 200, 207};
        assertThat(MathToolSuite.tightestHoldingK(lhs, rhs, 0.0)).isEqualTo(0.5, offset(1e-9));
    }

    @Test
    void subtractsTheIntercept() {
        // (lhs − c)/rhs: ({90,140} − 8)/{100,200} = {0.82, 0.66} → max 0.82.
        double[] lhs = {90, 140};
        double[] rhs = {100, 200};
        assertThat(MathToolSuite.tightestHoldingK(lhs, rhs, 8.0)).isEqualTo(0.82, offset(1e-9));
    }

    @Test
    void skipsNearZeroRhs_andHandlesBadInput() {
        assertThat(MathToolSuite.tightestHoldingK(new double[]{5, 9}, new double[]{0.0, 10}, 0.0))
                .isEqualTo(0.9, offset(1e-9));   // first window (rhs≈0) skipped
        assertThat(MathToolSuite.tightestHoldingK(new double[0], new double[0], 0.0))
                .isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(MathToolSuite.tightestHoldingK(new double[]{1}, new double[]{1, 2}, 0.0))
                .isEqualTo(Double.NEGATIVE_INFINITY);   // mismatched lengths
    }
}
