package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 23.5 — verify_conjecture tool contract.
 *
 * <p>Determinism check + held-out-from-mining check (the prototype's
 * windows lived in offsets the Python miner used: 100, 1k, 5k, 10k, 50k,
 * 100k, 500k, 1M; tests here pick offsets the miner never saw, like
 * 200k and 1.2M, so we're verifying on truly out-of-sample data).
 */
class ConjectureVerifierTest {

    @Test
    void abundantDensityBoundHoldsOnHeldOutWindow() {
        // The Python prototype found `abundant_density <= 0.2515` (pure
        // bound). Asymptotic density is ~0.2474 with small fluctuations.
        // Held-out region: offset 200_000 (miner saw only 100k and 500k).
        ConjectureVerifier verifier = new ConjectureVerifier();
        ConjectureVerifier.VerifyResult result = verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.2515,
                200_000, 500, 5);

        assertTrue(result.held(),
                "abundant_density <= 0.2515 should hold on held-out windows; worst=" + result.worstMargin());
        assertEquals(5, result.windowsEvaluated());
        assertEquals(5, result.windows().size());
        // Each window's density should be close to 0.247 ± small noise.
        for (ConjectureVerifier.WindowReport w : result.windows()) {
            assertTrue(w.lhsValue() < 0.30, "abundant density unexpectedly high in " + w);
            assertTrue(w.lhsValue() > 0.20, "abundant density unexpectedly low in " + w);
        }
    }

    @Test
    void overlyTightBoundIsRefutedOnHeldOutWindow() {
        // A deliberately-too-tight bound: claim abundant_density <= 0.10.
        // Reality is ~0.247, so margin ~= +0.15 — well above 2*TOL = 0.04.
        // Tool must refute.
        ConjectureVerifier verifier = new ConjectureVerifier();
        ConjectureVerifier.VerifyResult result = verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.10,
                200_000, 500, 3);

        assertFalse(result.held(), "an obviously-too-tight bound should be refuted");
        assertTrue(result.worstMargin() > 0.10,
                "worst margin should be substantially positive; got " + result.worstMargin());
    }

    @Test
    void deterministicForFixedWindow() {
        // Same window + same conjecture → bit-identical result. Critical
        // for the LLM seeing reproducible evidence across paper drafts.
        ConjectureVerifier verifier = new ConjectureVerifier();
        ConjectureVerifier.VerifyResult r1 = verifier.verify(
                "happy_density", "prime_density", 0.0, 0.20,
                1_200_000, 500, 5);
        ConjectureVerifier.VerifyResult r2 = verifier.verify(
                "happy_density", "prime_density", 0.0, 0.20,
                1_200_000, 500, 5);

        assertEquals(r1.held(), r2.held());
        assertEquals(r1.worstMargin(), r2.worstMargin());
        assertEquals(r1.worstWindowStart(), r2.worstWindowStart());
        assertEquals(r1.windows().size(), r2.windows().size());
        for (int i = 0; i < r1.windows().size(); i++) {
            assertEquals(r1.windows().get(i), r2.windows().get(i));
        }
    }

    @Test
    void worstMarginIsTrulyTheWorst() {
        // Sanity: the worst margin returned is the max across all per-window margins.
        ConjectureVerifier verifier = new ConjectureVerifier();
        ConjectureVerifier.VerifyResult result = verifier.verify(
                "prime_density", "happy_density", 0.0, 0.05,  // intentionally loose
                300_000, 500, 5);

        double maxObserved = result.windows().stream()
                .mapToDouble(ConjectureVerifier.WindowReport::margin).max().orElseThrow();
        assertEquals(maxObserved, result.worstMargin(), 1e-12);
    }

    @Test
    void rejectsBadInputs() {
        ConjectureVerifier verifier = new ConjectureVerifier();
        // Unknown scalar name
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                "made_up_scalar", "prime_density", 0.0, 0.1, 200_000, 500, 5));
        // Negative window_start
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.5, -1, 500, 5));
        // Zero / negative window_size
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.5, 200_000, 0, 5));
        // Work cap exceeded
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.5, 200_000,
                ConjectureVerifier.MAX_WORK + 1, 1));
        // Out of MAX_N range
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.5,
                ConjectureVerifier.MAX_N, 1000, 5));
    }

    @Test
    void defaultNumWindowsIsFive() {
        ConjectureVerifier verifier = new ConjectureVerifier();
        ConjectureVerifier.VerifyResult result = verifier.verify(
                "abundant_density", "prime_density", 0.0, 0.30, 200_000, 500);

        assertEquals(ConjectureVerifier.DEFAULT_NUM_WINDOWS, result.windowsEvaluated());
        assertEquals(ConjectureVerifier.DEFAULT_NUM_WINDOWS, result.windows().size());
    }
}
