package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rec #5 (2026-05-28, research/a3289005) — pins {@link
 * MathToolSuite#resolveSweepParams}. param1/param2 are abstract sweep knobs
 * (integer position 10–99, scale 1.0–9.0), NOT physical quantities. The resolver
 * auto-orients a clean integer transpose but rejects physical values with a clear
 * error instead of silently mangling them. Pure helper → no Mockito, runs on this
 * dev shell's JDK.
 */
class MathToolSuiteSweepParamsTest {

    @Test
    void directValidKnobsPassUnchanged() {
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(16, 3.5);
        assertThat(r.ok()).isTrue();
        assertThat(r.swapped()).isFalse();
        assertThat(r.param1()).isEqualTo(16);
        assertThat(r.param2()).isEqualTo(3.5);
    }

    @Test
    void cleanIntegerTransposeIsAutoOriented() {
        // P-a328-961: model put the scale in param1 and the integer position in
        // param2 (4.9, 53). 53 is an integer → genuine transpose → auto-orient.
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(4.9, 53);
        assertThat(r.ok()).isTrue();
        assertThat(r.swapped()).isTrue();
        assertThat(r.param1()).isEqualTo(53);
        assertThat(r.param2()).isEqualTo(4.9);
    }

    @Test
    void integerValuedFloatTransposeStillOriented() {
        // "53.0" is integer-like → still a clean transpose.
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(2.0, 53.0);
        assertThat(r.ok()).isTrue();
        assertThat(r.swapped()).isTrue();
        assertThat(r.param1()).isEqualTo(53);
    }

    @Test
    void physicalValuesAreRejectedNotSilentlySwapped() {
        // P-a328-1000: physical AU 3.5/15.8. The old guard swapped to
        // param1=16/param2=3.5 and swept a=1.30–2.05 AU. 15.8 is fractional →
        // not a transposed position knob → reject with a clear error.
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(3.5, 15.8);
        assertThat(r.ok()).isFalse();
        assertThat(r.error())
                .contains("ABSTRACT sweep knobs")
                .contains("do not pass physical values")
                .contains("param1=3.5")
                .contains("param2=15.8");
    }

    @Test
    void genuinelyOutOfRangeRejects() {
        // P-a328-967 sent param1=7 (below the 10 floor) with no valid transpose.
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(7, 2.0);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("Invalid params");
    }

    @Test
    void scaleOutOfRangeInBothOrientationsRejects() {
        // (50, 12.0): 50 is a valid position but 12 is not a valid scale, and the
        // swap (param1=12, param2=50) puts 50 outside the 1.0–9.0 scale band.
        MathToolSuite.SweepParams r = MathToolSuite.resolveSweepParams(50, 12.0);
        assertThat(r.ok()).isFalse();
    }
}
