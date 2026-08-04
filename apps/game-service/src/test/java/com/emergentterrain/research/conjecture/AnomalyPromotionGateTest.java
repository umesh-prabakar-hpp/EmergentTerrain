package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 7 — 30.6 adversarial promotion gate. Pins: survival requires the
 * hold regime to stay clean AND the break to replicate on every attempt; either
 * failure refutes with a precise reason; no witness → no promotion. The verifier
 * is a lambda (Mockito-free per the dev-shell JDK25 constraint).
 */
class AnomalyPromotionGateTest {

    private static Inequality anomaly() {
        Inequality ineq = new Inequality("alpha", "beta", 2.0, 1.5);
        ineq.confirmedRuns = 7;
        ineq.refutedBy.add(new Witness("run-1", 1_500_000L, 4.2));
        return ineq;
    }

    private static ConjectureVerifier.VerifyResult result(boolean held, double worst) {
        return new ConjectureVerifier.VerifyResult(
                "alpha", "beta", 2.0, 1.5, 10, held, worst, 0L, List.of());
    }

    @Test
    void survivesWhenHoldHoldsAndBreakReplicatesEveryAttempt() {
        AnomalyPromotionGate.Verdict v = AnomalyPromotionGate.replicate(
                anomaly(),
                // hold regime (small starts) holds; break regime (near witness) violates
                (lhs, rhs, k, c, start, size, n) ->
                        start < 1_000_000L ? result(true, -3.0) : result(false, +4.0),
                100_000L, 1_000, 3);
        assertThat(v.survived()).isTrue();
        assertThat(v.attempts()).isEqualTo(3);
        assertThat(v.breakReplications()).isEqualTo(3);
        assertThat(v.reason()).isEqualTo("survived_3_attempts");
    }

    @Test
    void holdRegimeViolationRefutesImmediately() {
        // Violations everywhere → globally false bound, not regime-anomalous.
        AnomalyPromotionGate.Verdict v = AnomalyPromotionGate.replicate(
                anomaly(),
                (lhs, rhs, k, c, start, size, n) -> result(false, +9.9),
                100_000L, 1_000, 3);
        assertThat(v.survived()).isFalse();
        assertThat(v.attempts()).isEqualTo(1);
        assertThat(v.reason()).startsWith("hold_regime_violated_attempt_1");
    }

    @Test
    void breakNotReplicatingRefutes() {
        // Holds everywhere → the original witness was noise.
        AnomalyPromotionGate.Verdict v = AnomalyPromotionGate.replicate(
                anomaly(),
                (lhs, rhs, k, c, start, size, n) -> result(true, -2.0),
                100_000L, 1_000, 3);
        assertThat(v.survived()).isFalse();
        assertThat(v.reason()).startsWith("break_did_not_replicate_attempt_1");
    }

    @Test
    void noWitnessNeverPromotes() {
        Inequality bare = new Inequality("alpha", "beta", 2.0, 1.5);
        AnomalyPromotionGate.Verdict v = AnomalyPromotionGate.replicate(
                bare, (lhs, rhs, k, c, start, size, n) -> result(true, -1.0),
                100_000L, 1_000, 3);
        assertThat(v.survived()).isFalse();
        assertThat(v.reason()).isEqualTo("no_witness");
    }
}
