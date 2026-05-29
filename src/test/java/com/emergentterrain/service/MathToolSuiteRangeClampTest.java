package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rec #6 (2026-05-28, research/a3289005) — pins the over-cap sandbox-range
 * auto-clamp ({@link MathToolSuite#clampSandboxEnd} / {@link
 * MathToolSuite#sandboxRangeClampNote}). The end-to-end behaviour is exercised by
 * {@link MathToolSuiteSandboxMetricsTest#overCapRangeIsAutoClampedInsteadOfRejected},
 * but that class is Mockito-based and can't instantiate on this dev shell's JDK;
 * these pure-helper tests run everywhere.
 */
class MathToolSuiteRangeClampTest {

    private static final int CAP = 5_000;

    @Test
    void withinCapRangePassesThroughUnchanged() {
        assertThat(MathToolSuite.clampSandboxEnd(1, 100, CAP)).isEqualTo(100);
        assertThat(MathToolSuite.sandboxRangeClampNote(1, 100, CAP)).isEmpty();
    }

    @Test
    void exactlyAtCapIsNotClamped() {
        // span == cap is allowed (the original guard rejected only span > cap).
        assertThat(MathToolSuite.clampSandboxEnd(1, 1 + CAP, CAP)).isEqualTo(1 + CAP);
        assertThat(MathToolSuite.sandboxRangeClampNote(1, 1 + CAP, CAP)).isEmpty();
    }

    @Test
    void overCapRangeIsClampedToStartPlusCap() {
        // P-a328-997: requested [3650, 10427) span 6777 > 5000 → [3650, 8650).
        assertThat(MathToolSuite.clampSandboxEnd(3650, 10427, CAP)).isEqualTo(8650);
        String note = MathToolSuite.sandboxRangeClampNote(3650, 10427, CAP);
        assertThat(note)
                .contains("auto-clamped")
                .contains("[3650,10427)")     // requested
                .contains("[3650,8650)")      // computed
                .contains("cite this range");
    }

    @Test
    void clampNoteIsEmptyWhenNoClamp() {
        assertThat(MathToolSuite.sandboxRangeClampNote(1, 5000, CAP)).isEmpty();
    }
}
