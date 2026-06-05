package com.emergentterrain.research.conjecture;

import java.util.List;

/**
 * Phase 30 Wave 7 — 30.6 adversarial promotion gate. An anomaly (a bound that was
 * a real pattern first — confirmed ≥ minConfirmed runs — then broke surprisingly
 * hard, surviving 30.1's MCC) may only be PROMOTED to an
 * {@code UNEXPLAINED_OBSERVATION} paper after independent refutation attempts
 * fail to break it. Each attempt re-runs the bound at FRESH windows in both
 * regimes, trying to refute the anomaly's signature (regime-dependence):
 *
 * <ul>
 *   <li><b>Hold-regime attempt</b> — fresh windows in the regime where the bound
 *       was confirmed. If violations appear here too, the bound is just globally
 *       false (the original confirmations were luck), NOT regime-anomalous →
 *       refuted.</li>
 *   <li><b>Break-regime attempt</b> — fresh windows around the recorded witness.
 *       If the violation does NOT reproduce there, the witness was noise →
 *       refuted.</li>
 * </ul>
 *
 * Only an anomaly whose pattern survives every attempt (still holds where it
 * held, still breaks where it broke) is promotion-worthy. Attempt offsets are
 * derived deterministically from the attempt index (no RNG), so the gate is
 * reproducible. The verifier is abstracted behind {@link WindowVerifier} so the
 * decision logic is unit-testable without a sidecar.
 */
public final class AnomalyPromotionGate {

    private AnomalyPromotionGate() {
    }

    /** Minimal verify surface ({@code ConjectureVerifier::verify} conforms). */
    @FunctionalInterface
    public interface WindowVerifier {
        ConjectureVerifier.VerifyResult verify(
                String lhs, String rhs, double k, double c,
                long windowStart, int windowSize, int numWindows);
    }

    /**
     * @param survived       true → publishable as UNEXPLAINED_OBSERVATION
     * @param attempts       refutation attempts run (each = hold + break probe)
     * @param holdViolations windows that violated in the hold regime (any > tolerated → refuted)
     * @param breakReplications attempts in which the break regime reproduced a violation
     * @param reason         machine-readable verdict
     */
    public record Verdict(boolean survived, int attempts, int holdViolations,
                          int breakReplications, String reason) {
    }

    /** Windows sampled per regime per attempt. */
    static final int WINDOWS_PER_PROBE = 10;
    /** Deterministic per-attempt jitter so each attempt sees fresh windows. */
    private static final long[] ATTEMPT_OFFSETS = {7L, 1009L, 5003L, 20011L, 50021L, 90001L};

    /**
     * @param ineq        the refuted bound (k, c, witnesses)
     * @param holdStart   a window start inside the confirmed regime (e.g. the miner's
     *                    smallest training offset)
     * @param windowSize  probe window size
     * @param attempts    independent refutation attempts (≥1; capped by available offsets)
     */
    public static Verdict replicate(Inequality ineq, WindowVerifier verifier,
                                    long holdStart, int windowSize, int attempts) {
        List<Witness> witnesses = ineq.refutedBy();
        if (witnesses == null || witnesses.isEmpty()) {
            return new Verdict(false, 0, 0, 0, "no_witness");
        }
        long breakCenter = witnesses.get(witnesses.size() - 1).tick();
        int n = Math.max(1, Math.min(attempts, ATTEMPT_OFFSETS.length));
        int holdViolations = 0;
        int breakReplications = 0;
        for (int i = 0; i < n; i++) {
            long jitter = ATTEMPT_OFFSETS[i];
            // Hold-regime probe: fresh windows near (but not identical to) the
            // confirmed regime. ANY violation here breaks the anomaly's premise.
            ConjectureVerifier.VerifyResult hold = verifier.verify(
                    ineq.lhs, ineq.rhs, ineq.k, ineq.c,
                    Math.max(1, holdStart + jitter), windowSize, WINDOWS_PER_PROBE);
            if (!hold.held()) {
                holdViolations++;
                return new Verdict(false, i + 1, holdViolations, breakReplications,
                        "hold_regime_violated_attempt_" + (i + 1)
                                + "_worst_" + String.format("%.4f", hold.worstMargin()));
            }
            // Break-regime probe: fresh windows straddling the witness. The
            // violation must REPRODUCE; a clean pass means the witness was noise.
            long breakStart = Math.max(1,
                    breakCenter - (long) windowSize * (WINDOWS_PER_PROBE / 2) + jitter);
            ConjectureVerifier.VerifyResult brk = verifier.verify(
                    ineq.lhs, ineq.rhs, ineq.k, ineq.c,
                    breakStart, windowSize, WINDOWS_PER_PROBE);
            if (brk.held()) {
                return new Verdict(false, i + 1, holdViolations, breakReplications,
                        "break_did_not_replicate_attempt_" + (i + 1));
            }
            breakReplications++;
        }
        return new Verdict(true, n, holdViolations, breakReplications,
                "survived_" + n + "_attempts");
    }
}
