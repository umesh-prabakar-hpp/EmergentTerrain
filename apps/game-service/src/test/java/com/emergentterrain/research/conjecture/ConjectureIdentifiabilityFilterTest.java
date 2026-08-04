package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 1 — unit tests for {@link ConjectureIdentifiabilityFilter} (FIM slope identifiability).
 */
class ConjectureIdentifiabilityFilterTest {

    private static final double MAX_REL_SE = 1.0;

    private static List<Map<String, Double>> samples(double[] lhs, double[] rhs) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 0; i < lhs.length; i++) {
            out.add(Map.of("lhs", lhs[i], "rhs", rhs[i]));
        }
        return out;
    }

    @Test
    void wellPinnedSlopeNotFlagged() {
        // lhs tracks rhs tightly → small relative SE → the slope IS pinned → keep.
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] lhs = {2.0, 4.1, 5.9, 8.0, 10.1, 11.9, 14.0, 16.1, 17.9, 20.0};  // ≈ 2*rhs
        var r = ConjectureIdentifiabilityFilter.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 2.0, MAX_REL_SE);
        assertThat(r.flagged()).isFalse();
        assertThat(r.reason()).isEqualTo("ok");
        assertThat(r.relStdErr()).isLessThan(MAX_REL_SE);
    }

    @Test
    void unpinnedSlopeIsFlagged() {
        // lhs is noise unrelated to rhs, but a bound was fit with a non-zero slope claim → the OLS
        // slope is indistinguishable from 0 (huge relative SE) → the data doesn't pin k → flagged.
        double[] rhs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] lhs = {5, 4, 6, 5, 5, 6, 4, 5, 6, 5};
        var r = ConjectureIdentifiabilityFilter.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 1.5, MAX_REL_SE);
        assertThat(r.flagged()).isTrue();
        assertThat(r.reason()).isEqualTo("slope_unpinned");
    }

    @Test
    void degenerateRhsIsFlagged() {
        // rhs constant → Σ(rhs-mean)² = 0 → Fisher information singular → slope unidentifiable.
        double[] rhs = {3, 3, 3, 3, 3, 3, 3, 3};
        double[] lhs = {1, 2, 3, 4, 5, 6, 7, 8};
        var r = ConjectureIdentifiabilityFilter.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 1.0, MAX_REL_SE);
        assertThat(r.flagged()).isTrue();
        assertThat(r.reason()).isEqualTo("rhs_degenerate");
    }

    @Test
    void constantBoundIsOutOfScope() {
        // k≈0 claims no slope → TrivialityFilter owns it, not this gate → never flagged here.
        double[] rhs = {3, 3, 3, 3, 3, 3, 3, 3};
        double[] lhs = {1, 2, 3, 4, 5, 6, 7, 8};
        var r = ConjectureIdentifiabilityFilter.evaluate(
                samples(lhs, rhs), "lhs", "rhs", /*k*/ 0.0, MAX_REL_SE);
        assertThat(r.flagged()).isFalse();
        assertThat(r.reason()).isEqualTo("constant_bound_out_of_scope");
    }

    @Test
    void tooFewSamplesNotFlagged() {
        var r = ConjectureIdentifiabilityFilter.evaluate(
                samples(new double[]{1, 2, 3}, new double[]{1, 5, 2}), "lhs", "rhs", 1.0, MAX_REL_SE);
        assertThat(r.flagged()).isFalse();
        assertThat(r.reason()).isEqualTo("insufficient_samples");
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(ConjectureIdentifiabilityFilter.evaluate(null, "lhs", "rhs", 1.0, MAX_REL_SE)
                .flagged()).isFalse();
    }
}
