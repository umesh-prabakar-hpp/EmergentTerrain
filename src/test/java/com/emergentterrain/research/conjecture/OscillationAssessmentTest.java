package com.emergentterrain.research.conjecture;

import com.emergentterrain.research.conjecture.OscillationAssessment.Assessment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 2026-06-10 (lever D, frame 2) — oscillation/sign-change assessment for mertens_delta-like quantities. */
class OscillationAssessmentTest {

    @Test
    void detectsCentredOscillation_withSignChangesAndEnvelope() {
        // M(x)-like: swings around 0; scale = √x so envelope = max|M|/√x.
        double[] v = {-120, 90, -60, 140, -30};
        long[] s = {1_000_000, 1_200_000, 1_400_000, 1_600_000, 1_800_000};
        double[] scale = {1000, 1095, 1183, 1265, 1342};   // ~√x
        Assessment a = OscillationAssessment.assess(v, s, scale);
        assertThat(a.signChanges()).isEqualTo(4);
        assertThat(a.amplitude()).isEqualTo(140.0);
        assertThat(a.ampWindowStart()).isEqualTo(1_600_000);
        assertThat(a.oscillates()).isTrue();               // mean ≈ +4, amplitude 140 → centred
        // envelope = max over windows of |v|/scale = 120/1000 = 0.12 (window 0), not the amplitude window.
        assertThat(a.envelope()).isEqualTo(120.0 / 1000, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void oneSidedQuantity_isNotAnOscillation() {
        double[] v = {10, 12, 11, 13, 14};                 // all positive, no sign change
        long[] s = {1_000_000, 1_200_000, 1_400_000, 1_600_000, 1_800_000};
        Assessment a = OscillationAssessment.assess(v, s, null);
        assertThat(a.signChanges()).isZero();
        assertThat(a.oscillates()).isFalse();
        assertThat(a.envelope()).isNaN();                  // no scale supplied
    }

    @Test
    void rejectsMismatchedScale() {
        assertThatThrownBy(() -> OscillationAssessment.assess(
                new double[]{1, -1}, new long[]{1, 2}, new double[]{1.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
