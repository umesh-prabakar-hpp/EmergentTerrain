package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.emergentterrain.research.conjecture.MultipleComparisonsCorrection.Method;

/**
 * Phase 30 Wave 4 — unit tests for {@link MultipleComparisonsCorrection}. Worked example
 * (m=5, α=0.05): sorted p = {0.005, 0.01, 0.03, 0.5, 0.6}.
 * Bonferroni threshold α/m = 0.01 → rejects {0.005, 0.01}.
 * BH critical (k/m)·α = {0.01, 0.02, 0.03, 0.04, 0.05}; largest k with p_(k) ≤ crit is
 * k=3 (0.03 ≤ 0.03) → rejects {0.005, 0.01, 0.03}. So BH ⊇ Bonferroni.
 */
class MultipleComparisonsCorrectionTest {

    private static final double ALPHA = 0.05;
    // Deliberately shuffled input order to test index alignment.
    private static final double[] P = {0.5, 0.005, 0.03, 0.6, 0.01};
    // sorted-value → original index: 0.005→1, 0.01→4, 0.03→2, 0.5→0, 0.6→3

    @Test
    void bonferroniRejectsBelowAlphaOverM() {
        boolean[] r = MultipleComparisonsCorrection.reject(P, Method.BONFERRONI, ALPHA);
        // α/m = 0.01 → reject {0.005 (idx1), 0.01 (idx4)}
        assertThat(r).containsExactly(false, true, false, false, true);
    }

    @Test
    void benjaminiHochbergStepUpRejectsThroughLargestK() {
        boolean[] r = MultipleComparisonsCorrection.reject(P, Method.BENJAMINI_HOCHBERG, ALPHA);
        // rejects {0.005 (idx1), 0.01 (idx4), 0.03 (idx2)}
        assertThat(r).containsExactly(false, true, true, false, true);
    }

    @Test
    void bhRejectionSetSupersetsBonferroni() {
        boolean[] bonf = MultipleComparisonsCorrection.reject(P, Method.BONFERRONI, ALPHA);
        boolean[] bh = MultipleComparisonsCorrection.reject(P, Method.BENJAMINI_HOCHBERG, ALPHA);
        for (int i = 0; i < P.length; i++) {
            if (bonf[i]) {
                assertThat(bh[i]).as("BH must reject everything Bonferroni does (idx %d)", i).isTrue();
            }
        }
    }

    @Test
    void rejectsNoneWhenAllAboveThreshold() {
        double[] big = {0.2, 0.4, 0.6, 0.8, 0.99};
        assertThat(MultipleComparisonsCorrection.reject(big, Method.BENJAMINI_HOCHBERG, ALPHA))
                .containsExactly(false, false, false, false, false);
        assertThat(MultipleComparisonsCorrection.reject(big, Method.BONFERRONI, ALPHA))
                .containsExactly(false, false, false, false, false);
    }

    @Test
    void emptyFamilyIsSafe() {
        assertThat(MultipleComparisonsCorrection.reject(new double[]{}, Method.BENJAMINI_HOCHBERG, ALPHA))
                .isEmpty();
        assertThat(MultipleComparisonsCorrection.reject(null, Method.BONFERRONI, ALPHA)).isEmpty();
    }

    @Test
    void methodParseDefaultsToBh() {
        assertThat(Method.parse("bonferroni")).isEqualTo(Method.BONFERRONI);
        assertThat(Method.parse("bonf")).isEqualTo(Method.BONFERRONI);
        assertThat(Method.parse("bh")).isEqualTo(Method.BENJAMINI_HOCHBERG);
        assertThat(Method.parse("nonsense")).isEqualTo(Method.BENJAMINI_HOCHBERG);
        assertThat(Method.parse(null)).isEqualTo(Method.BENJAMINI_HOCHBERG);
    }
}
