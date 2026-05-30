package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding-regime probe applicability (room b72c4e10 fix). Pure — no Spring,
 * no sidecar, no Mockito.
 */
class BindingRegimeProbeTest {

    @Test
    void appliesOnlyToStartMonotoneScalarWithPositiveK() {
        // The exact case from P-b72c-15: lhs <= 2.0 * log_window_start + c.
        assertTrue(BindingRegimeProbe.appliesTo("log_window_start", 2.0));
        assertTrue(BindingRegimeProbe.appliesTo("log_window_start", 0.0001));
    }

    @Test
    void doesNotApplyWhenKIsNonPositive() {
        // k <= 0 means the ceiling is flat or decreasing in start; smallest
        // start is no longer the tightest, so the probe is not justified.
        assertFalse(BindingRegimeProbe.appliesTo("log_window_start", 0.0));
        assertFalse(BindingRegimeProbe.appliesTo("log_window_start", -1.0));
    }

    @Test
    void doesNotApplyToContentOrSizeScalars() {
        // These are functions of window content or window_size, not start, so a
        // small-start probe could fabricate a spurious refutation — stay silent.
        assertFalse(BindingRegimeProbe.appliesTo("prime_density", 2.0));
        assertFalse(BindingRegimeProbe.appliesTo("happy_density", 2.0));
        assertFalse(BindingRegimeProbe.appliesTo("sqrt_window_size", 2.0));
        assertFalse(BindingRegimeProbe.appliesTo("log_window_size", 2.0));
        assertFalse(BindingRegimeProbe.appliesTo("mertens_per_sqrt_W", 2.0));
        assertFalse(BindingRegimeProbe.appliesTo(null, 2.0));
    }

    @Test
    void shouldProbeOnlyBelowSmallestRequestedStart() {
        // P-b72c-15 requested starts >= 200_000; a probe at 2_000 is worth it.
        assertTrue(BindingRegimeProbe.shouldProbe("log_window_start", 2.0, 200_000, 2_000));
    }

    @Test
    void shouldNotProbeWhenProbeStartNotBelowRequested() {
        // Caller already started at/below the probe point → nothing tighter to test.
        assertFalse(BindingRegimeProbe.shouldProbe("log_window_start", 2.0, 2_000, 2_000));
        assertFalse(BindingRegimeProbe.shouldProbe("log_window_start", 2.0, 1_000, 2_000));
    }

    @Test
    void shouldNotProbeForInapplicableBoundOrNegativeStart() {
        assertFalse(BindingRegimeProbe.shouldProbe("prime_density", 2.0, 200_000, 2_000));
        assertFalse(BindingRegimeProbe.shouldProbe("log_window_start", 2.0, 200_000, -1));
    }
}
