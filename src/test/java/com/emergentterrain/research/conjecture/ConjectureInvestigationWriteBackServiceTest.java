package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.ConjectureInvestigationWriteBackService.HeldBound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-09 — discovery→pool write-back (step 1). Tests the pure parse +
 * write-back gate (the DB upsert is exercised only behind the flag at runtime).
 */
class ConjectureInvestigationWriteBackServiceTest {

    private static ToolInvocation verify(String resultText) {
        return new ToolInvocation("verify_conjecture", "{}", resultText, 0, "call_1");
    }

    private static final String HELD_AT_FAILURE =
            "verify_conjecture: max_prime_gap <= +1.0000 * log_sq_window_start + +0.0000 "
            + "HELD across 10 window(s) of size 500 starting at 1800000. "
            + "worst margin: -135.49096 at window starting 1810000.";

    /** A tight HELD line (relative slack 0.0900, within the 0.25 ceiling). */
    private static final String HELD_TIGHT =
            "verify_conjecture: max_prime_gap <= +0.7200 * log_sq_window_start + +1.5000 "
            + "HELD across 10 window(s) of size 500 starting at 1800000. "
            + "worst margin: -12.30000 at window starting 1810000. relative slack: 0.0900 "
            + "(|margin|/|bound|, smaller = tighter).";

    /** A loose HELD line (relative slack 0.6500, over the ceiling). */
    private static final String HELD_LOOSE =
            "verify_conjecture: max_prime_gap <= +1.0000 * log_sq_window_start + +0.0000 "
            + "HELD across 10 window(s) of size 500 starting at 1800000. "
            + "worst margin: -135.49096 at window starting 1810000. relative slack: 0.6500 "
            + "(|margin|/|bound|, smaller = tighter).";

    @Test
    void parsesAHeldBoundFromVerifyText() {
        List<HeldBound> bounds = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_AT_FAILURE)));
        assertThat(bounds).hasSize(1);
        HeldBound b = bounds.get(0);
        assertThat(b.lhs()).isEqualTo("max_prime_gap");
        assertThat(b.rhs()).isEqualTo("log_sq_window_start");
        assertThat(b.k()).isEqualTo(1.0);
        assertThat(b.c()).isEqualTo(0.0);
        assertThat(b.numWindows()).isEqualTo(10);
        assertThat(b.windowStart()).isEqualTo(1_800_000L);
        assertThat(b.relSlack()).isNull();   // legacy line carries no slack figure
    }

    @Test
    void parsesRelativeSlackWhenPresent() {
        HeldBound b = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TIGHT))).get(0);
        assertThat(b.relSlack()).isEqualTo(0.09);
        assertThat(b.k()).isEqualTo(0.72);
    }

    @Test
    void notWritable_whenTooLoose() {
        // lever B: a HELD bound whose binding-window relative slack exceeds the ceiling is the
        // flat-feature refutation dressed up, not a tight survivor.
        HeldBound loose = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_LOOSE))).get(0);
        assertThat(ConjectureInvestigationWriteBackService.writable(loose)).isFalse();
    }

    @Test
    void writable_whenTightEnough() {
        HeldBound tight = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TIGHT))).get(0);
        assertThat(ConjectureInvestigationWriteBackService.writable(tight)).isTrue();
    }

    @Test
    void tighter_prefersSmallerRelativeSlack() {
        // lever A: of two HELD verifications of the same relation, keep the tighter (smaller slack).
        HeldBound tight = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TIGHT))).get(0);
        HeldBound loose = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 10, 1_800_000L, -135.0, 0.65);
        assertThat(ConjectureInvestigationWriteBackService.tighter(tight, loose)).isSameAs(tight);
        assertThat(ConjectureInvestigationWriteBackService.tighter(loose, tight)).isSameAs(tight);
    }

    @Test
    void tighter_withoutSlack_prefersWorstMarginClosestToZero() {
        HeldBound nearer = new HeldBound("a", "b", 1.0, 0.0, 10, 1_800_000L, -3.0, null);
        HeldBound farther = new HeldBound("a", "b", 1.0, 0.0, 10, 1_800_000L, -50.0, null);
        assertThat(ConjectureInvestigationWriteBackService.tighter(nearer, farther)).isSameAs(nearer);
    }

    @Test
    void ignoresRefutedBounds() {
        String refuted = "verify_conjecture: max_prime_gap <= +2.0000 * abundant_density + "
                + "+35.0400 REFUTED across 50 window(s) of size 500 starting at 1800000. "
                + "worst margin: +42.60359 at window starting 1812003.";
        assertThat(ConjectureInvestigationWriteBackService.parseHeldBounds(List.of(verify(refuted))))
                .isEmpty();
    }

    @Test
    void writable_heldAtFailureRegion_withCoverage_andLegalInequality() {
        HeldBound b = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_AT_FAILURE))).get(0);
        assertThat(ConjectureInvestigationWriteBackService.writable(b)).isTrue();
    }

    @Test
    void notWritable_whenHeldOnlyAtEasyLowX() {
        // held near x=200,000 (below the 1M failure threshold) — proves nothing.
        HeldBound easy = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 10, 200_003L, -89.0, null);
        assertThat(ConjectureInvestigationWriteBackService.writable(easy)).isFalse();
    }

    @Test
    void notWritable_withTooFewWindows() {
        HeldBound thin = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 1, 1_800_000L, -135.0, null);
        assertThat(ConjectureInvestigationWriteBackService.writable(thin)).isFalse();
    }

    @Test
    void notWritable_whenLhsIsAWindowGeometryScalar() {
        // a window-geometry quantity as the LHS is a degenerate bound (triviality
        // filter), never a valid pool relation.
        HeldBound bad = new HeldBound("log_sq_window_start", "max_divisor_count",
                2.0, 0.0, 10, 1_800_000L, -5.0, null);
        assertThat(ConjectureInvestigationWriteBackService.writable(bad)).isFalse();
    }

    @Test
    void nullTrace_yieldsNoBounds() {
        assertThat(ConjectureInvestigationWriteBackService.parseHeldBounds(null)).isEmpty();
    }

    // ── Lever D — measured-invariant (frames 1-3) parsing ──────────────────────────────────

    private static final String CONST_CONVERGED =
            "verify_constant: twin_vs_prime_sq ~ 1.32050 over 5 window(s) of size 10000 starting at "
            + "1800000. CONVERGED to target 1.32032: worst |dev| 0.00210 at window starting 1900000, "
            + "relative spread 0.0016 (tol 0.0500).";
    private static final String GROWTH_CONVERGED =
            "verify_growth: max_divisor_count in wigert coords ~ 0.69500 over 5 window(s) of size 10000 "
            + "starting at 1800000. CONVERGED to growth constant 0.69315: worst |dev| 0.01000 at window "
            + "starting 1900000, relative spread 0.0144 (tol 0.1000).";
    private static final String OSC_ENVELOPE =
            "verify_oscillation: mertens_delta over 5 window(s) of size 10000 starting at 1800000: "
            + "4 sign change(s), amplitude 140.00000 at window starting 1600000, mean +4.00000, "
            + "mean|.| 88.00000. OSCILLATES (centred near 0). envelope max|mertens_delta|/sqrt_window_start = 0.12000.";

    @Test
    void parsesConstantConvergence_asConstantInvariant() {
        var ms = ConjectureInvestigationWriteBackService.parseMeasured(List.of(verify(CONST_CONVERGED)));
        assertThat(ms).hasSize(1);
        var m = ms.get(0);
        assertThat(m.kind()).isEqualTo(ConjectureInvestigationWriteBackService.KIND_CONSTANT);
        assertThat(m.lhs()).isEqualTo("twin_vs_prime_sq");
        assertThat(m.value()).isEqualTo(1.32032);
        assertThat(m.windowStart()).isEqualTo(1_800_000L);
        assertThat(m.relativeSpread()).isEqualTo(0.0016);
    }

    @Test
    void parsesGrowthConvergence_asConstantInvariant() {
        var m = ConjectureInvestigationWriteBackService.parseMeasured(List.of(verify(GROWTH_CONVERGED))).get(0);
        assertThat(m.kind()).isEqualTo(ConjectureInvestigationWriteBackService.KIND_CONSTANT);
        assertThat(m.lhs()).isEqualTo("max_divisor_count");
        assertThat(m.value()).isEqualTo(0.69315);
    }

    @Test
    void parsesOscillationEnvelope_asOscillationInvariant() {
        var m = ConjectureInvestigationWriteBackService.parseMeasured(List.of(verify(OSC_ENVELOPE))).get(0);
        assertThat(m.kind()).isEqualTo(ConjectureInvestigationWriteBackService.KIND_OSCILLATION);
        assertThat(m.lhs()).isEqualTo("mertens_delta");
        assertThat(m.value()).isEqualTo(0.12000);
    }

    @Test
    void doesNotParseNonConvergedConstant() {
        String notConverged = CONST_CONVERGED.replace("CONVERGED to target", "NOT converged to target");
        assertThat(ConjectureInvestigationWriteBackService.parseMeasured(List.of(verify(notConverged)))).isEmpty();
    }

    @Test
    void measuredWritable_requiresFailureRegion() {
        var failureRegion = new ConjectureInvestigationWriteBackService.MeasuredInvariant(
                "CONSTANT", "twin_vs_prime_sq", 1.32032, 1_800_000L, 0.0016);
        var lowX = new ConjectureInvestigationWriteBackService.MeasuredInvariant(
                "CONSTANT", "twin_vs_prime_sq", 1.32032, 200_000L, 0.0016);
        assertThat(ConjectureInvestigationWriteBackService.measuredWritable(failureRegion)).isTrue();
        assertThat(ConjectureInvestigationWriteBackService.measuredWritable(lowX)).isFalse();
    }

    @Test
    void rejectReason_writable_isEmpty() {
        HeldBound tight = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TIGHT))).get(0);
        assertThat(ConjectureInvestigationWriteBackService.rejectReason(tight)).isEmpty();
    }

    @Test
    void rejectReason_namesTheFailingGate() {
        HeldBound subRegion = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 10, 200_003L, -89.0, null);
        assertThat(ConjectureInvestigationWriteBackService.rejectReason(subRegion))
                .startsWith("sub-failure-region");
        HeldBound loose = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 10, 1_800_000L, -135.0, 0.65);
        assertThat(ConjectureInvestigationWriteBackService.rejectReason(loose))
                .startsWith("too-loose");
    }
}
