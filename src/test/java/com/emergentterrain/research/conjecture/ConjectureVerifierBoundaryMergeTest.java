package com.emergentterrain.research.conjecture;

import com.emergentterrain.research.conjecture.ConjectureVerifier.VerifyResult;
import com.emergentterrain.research.conjecture.ConjectureVerifier.WindowReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConjectureVerifier#mergeBoundaryProbe} merge semantics (room
 * b72c4e10 fix). Pure — builds {@link VerifyResult}s by hand, no sidecar.
 */
class ConjectureVerifierBoundaryMergeTest {

    private static VerifyResult held(long start, double margin) {
        return new VerifyResult("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621,
                1, true, margin, start,
                List.of(new WindowReport(start, 0.5, 5.3, 0.0, 0.5 - margin, margin, true)));
    }

    private static VerifyResult refuted(long start, double margin) {
        return new VerifyResult("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621,
                1, false, margin, start,
                List.of(new WindowReport(start, 1.2, 3.4, 0.0, 1.2 - margin, margin, false)));
    }

    @Test
    void refutingProbeFlipsHeldVerdict() {
        // The P-b72c-15 scenario: requested windows held at large start, but the
        // binding-regime probe at small start refutes.
        VerifyResult requested = held(200_000, -0.87);
        VerifyResult probe = refuted(2_000, +2.01);

        VerifyResult merged = ConjectureVerifier.mergeBoundaryProbe(requested, probe);

        assertFalse(merged.held(), "a refuting boundary probe must flip HELD to REFUTED");
        assertEquals(+2.01, merged.worstMargin(), 1e-9, "worst margin is the probe's positive margin");
        assertEquals(2_000, merged.worstWindowStart());
        assertEquals(2, merged.windowsEvaluated());
    }

    @Test
    void probeWindowIsPrependedSoDetailReadsLowToHigh() {
        VerifyResult requested = held(200_000, -0.87);
        VerifyResult probe = refuted(2_000, +2.01);

        VerifyResult merged = ConjectureVerifier.mergeBoundaryProbe(requested, probe);

        assertEquals(2_000, merged.windows().get(0).windowStart(), "probe (smaller start) first");
        assertEquals(200_000, merged.windows().get(1).windowStart());
    }

    @Test
    void holdingProbePreservesHeldAndKeepsWorseMargin() {
        // If the bound genuinely holds even at the binding regime, the verdict
        // stays HELD and the worst margin is whichever window is tighter.
        VerifyResult requested = held(200_000, -3.0);
        VerifyResult probe = held(2_000, -0.4);

        VerifyResult merged = ConjectureVerifier.mergeBoundaryProbe(requested, probe);

        assertTrue(merged.held());
        assertEquals(-0.4, merged.worstMargin(), 1e-9, "worst margin is the less-negative (tighter) one");
        assertEquals(2_000, merged.worstWindowStart());
        assertEquals(2, merged.windowsEvaluated());
    }
}
