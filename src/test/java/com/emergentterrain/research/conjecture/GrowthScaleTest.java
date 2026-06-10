package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.data.Offset;

/** 2026-06-10 (lever D, frame 3) — the Wigert/log-log growth transform for max_divisor_count-like quantities. */
class GrowthScaleTest {

    @Test
    void wigertTransform_matchesLogTimesLnlnOverLn() {
        // x=1,800,000: ln x ≈ 14.4036, ln ln x ≈ 2.66747. d_max ≈ 240: log(240) ≈ 5.48064.
        // wigert ratio = 5.48064 * 2.66747 / 14.4036 ≈ 1.01509.
        double r = GrowthScale.transform("wigert", 240.0, 1_800_000L);
        assertThat(r).isEqualTo(1.01509, Offset.offset(1e-4));
    }

    @Test
    void loglogTransform_doesNotTakeLogOfValue() {
        double r = GrowthScale.transform("loglog", 5.48064, 1_800_000L);
        // same ratio as wigert(240) since loglog feeds the already-logged value.
        assertThat(r).isEqualTo(1.01509, Offset.offset(1e-4));
    }

    @Test
    void wigertLimitIsLn2() {
        assertThat(GrowthScale.WIGERT_LIMIT).isEqualTo(Math.log(2.0));
    }

    @Test
    void unknownScale_throws() {
        assertThatThrownBy(() -> GrowthScale.transform("bogus", 1.0, 1_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
