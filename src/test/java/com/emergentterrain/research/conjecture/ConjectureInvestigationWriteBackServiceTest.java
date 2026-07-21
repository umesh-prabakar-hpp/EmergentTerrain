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

    private static ToolInvocation verification(double k, double c, long start, long size, int windows,
                                               String resultText, String callId) {
        String args = "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\","
                + "\"k\":" + k + ",\"c\":" + c + ",\"window_start\":" + start
                + ",\"window_size\":" + size + ",\"num_windows\":" + windows + "}";
        return new ToolInvocation("verify_conjecture", args, resultText, 0, callId);
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

    /** A two-term HELD line in the exact MathToolSuite summary form: lhs <= k*rhs + k2*rhs2 + c. */
    private static final String HELD_TWO_TERM =
            "verify_conjecture: max_prime_gap <= +0.7200 * log_sq_window_start "
            + "+ +0.5000 * sqrt_window_start + +1.5000 "
            + "HELD across 10 window(s) of size 500 starting at 1800000. "
            + "worst margin: -12.30000 at window starting 1810000. relative slack: 0.0900 "
            + "(|margin|/|bound|, smaller = tighter).";

    @Test
    void parsesATwoTermHeldBound() {
        // G2 capture fix: before, the one-term regex could not match `+ k2 * rhs2` and silently dropped
        // every tightened two-term bound. It must now parse both terms.
        HeldBound b = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TWO_TERM))).get(0);
        assertThat(b.lhs()).isEqualTo("max_prime_gap");
        assertThat(b.rhs()).isEqualTo("log_sq_window_start");
        assertThat(b.k()).isEqualTo(0.72);
        assertThat(b.rhs2()).isEqualTo("sqrt_window_start");
        assertThat(b.k2()).isEqualTo(0.5);
        assertThat(b.c()).isEqualTo(1.5);
        assertThat(b.isTwoTerm()).isTrue();
        assertThat(b.relSlack()).isEqualTo(0.09);
        assertThat(ConjectureInvestigationWriteBackService.writable(b)).isTrue();
    }

    @Test
    void oneTermLineStillParsesWithNullSecondTerm() {
        // The optional two-term segment must not change one-term parsing: rhs2 stays null, k2 zero.
        HeldBound b = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_TIGHT))).get(0);
        assertThat(b.rhs2()).isNull();
        assertThat(b.k2()).isEqualTo(0.0);
        assertThat(b.isTwoTerm()).isFalse();
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
    void bindingPair_requiresNearbyRefuteOnTheSelectedTraceAndSameGrid() {
        ToolInvocation held = verification(0.7200, 1.5000, 1_800_000L, 500, 10,
                HELD_TIGHT, "call_1");
        String refuted = "verify_conjecture: max_prime_gap <= +0.7190 * log_sq_window_start + +1.5000 "
                + "REFUTED across 10 window(s) of size 500 starting at 1800000. "
                + "worst margin: +0.05000 at window starting 1810000.";
        ToolInvocation nearbyRefute = verification(0.7190, 1.5000, 1_800_000L, 500, 10,
                refuted, "call_2");
        HeldBound bound = ConjectureInvestigationWriteBackService.parseHeldBounds(List.of(held)).get(0);

        assertThat(ConjectureInvestigationWriteBackService.bindingPairRejectReason(
                bound, List.of(held, nearbyRefute))).isEmpty();
    }

    @Test
    void bindingPair_rejectsARefuteFromDifferentGridOrAnUnselectedTrace() {
        ToolInvocation held = verification(0.7200, 1.5000, 1_800_000L, 500, 10,
                HELD_TIGHT, "call_1");
        String refuted = "verify_conjecture: max_prime_gap <= +0.7190 * log_sq_window_start + +1.5000 "
                + "REFUTED across 10 window(s) of size 500 starting at 1900000. "
                + "worst margin: +0.05000 at window starting 1910000.";
        ToolInvocation wrongGrid = verification(0.7190, 1.5000, 1_900_000L, 500, 10,
                refuted, "call_2");
        HeldBound bound = ConjectureInvestigationWriteBackService.parseHeldBounds(List.of(held)).get(0);

        assertThat(ConjectureInvestigationWriteBackService.bindingPairRejectReason(
                bound, List.of(held))).isEqualTo("missing-nearby-refute-same-grid");
        assertThat(ConjectureInvestigationWriteBackService.bindingPairRejectReason(
                bound, List.of(held, wrongGrid))).isEqualTo("missing-nearby-refute-same-grid");
    }

    @Test
    void writeBack_doesNotPromoteATightHoldWithoutItsSelectedTraceCounterpart() {
        ToolInvocation held = verification(0.7200, 1.5000, 1_800_000L, 500, 10,
                HELD_TIGHT, "call_1");
        var service = new ConjectureInvestigationWriteBackService(null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);

        // No repository call is possible or needed: the binding-pair gate must stop before upsert.
        assertThat(service.writeBack(List.of(held), "P-selected-only")).isZero();
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
    void notWritable_whenLegacyHeldOutputHasNoTightnessEvidence() {
        HeldBound b = ConjectureInvestigationWriteBackService.parseHeldBounds(
                List.of(verify(HELD_AT_FAILURE))).get(0);
        assertThat(ConjectureInvestigationWriteBackService.writable(b)).isFalse();
        assertThat(ConjectureInvestigationWriteBackService.rejectReason(b))
                .isEqualTo("missing-relative-slack");
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

    @Test
    void parseHeldBounds_findsEveryBoundInOneBlob() {
        // The trace-file path (rec 2 facet B) wraps the whole .jsonl as ONE synthetic ToolInvocation;
        // parseHeldBounds must regex-sweep it and find every distinct HELD line, not just the first.
        String fileLike = HELD_TIGHT + "\n ...json noise... \n"
                + "verify_conjecture: max_prime_gap <= +0.4990 * log_sq_window_start + +0.0000 HELD across "
                + "10 window(s) of size 5000 starting at 1500000. worst margin: -0.50000 at window starting "
                + "1500000. relative slack: 0.0100 (|margin|/|bound|, smaller = tighter).";
        var bounds = ConjectureInvestigationWriteBackService.parseHeldBounds(List.of(verify(fileLike)));
        assertThat(bounds).extracting(HeldBound::k).contains(0.72, 0.499);
    }

    // ── Lever D — measured-invariant (frames 1-3) parsing ──────────────────────────────────

    private static final String CONST_CONVERGED =
            "verify_constant: twin_vs_prime_sq ~ 1.31817 over 20 window(s) of size 5000 starting at "
            + "1800000. CONVERGED to target 1.32032: mean rel-dev 0.0016, per-window spread 0.2900 "
            + "(worst |dev| 0.37088 at window starting 2010000) (tol 0.0500).";
    private static final String GROWTH_CONVERGED =
            "verify_growth: max_divisor_count in wigert coords ~ 0.69500 over 5 window(s) of size 10000 "
            + "starting at 1800000. CONVERGED to growth constant 0.69315: mean rel-dev 0.0144, "
            + "per-window spread 0.0500 (worst |dev| 0.03000 at window starting 1900000) (tol 0.1000).";
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
    void keepsMeasurementsAtDistinctWindows_soDeepestCanBeChosen() {
        // The live bug: a low-x measurement (300k) shadowed the failure-region one (1.9M) because the
        // parse deduped to the first per (kind,lhs). Now both windows survive parsing.
        String lowX = OSC_ENVELOPE.replace("starting at 1800000", "starting at 300000");
        String deep = OSC_ENVELOPE.replace("starting at 1800000", "starting at 1900000");
        var ms = ConjectureInvestigationWriteBackService.parseMeasured(List.of(verify(lowX), verify(deep)));
        assertThat(ms).hasSize(2);
        assertThat(ms).extracting(
                ConjectureInvestigationWriteBackService.MeasuredInvariant::windowStart)
                .containsExactlyInAnyOrder(300_000L, 1_900_000L);
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
