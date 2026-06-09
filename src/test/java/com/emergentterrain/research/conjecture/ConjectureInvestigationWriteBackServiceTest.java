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
                1.0, 0.0, 10, 200_003L, -89.0);
        assertThat(ConjectureInvestigationWriteBackService.writable(easy)).isFalse();
    }

    @Test
    void notWritable_withTooFewWindows() {
        HeldBound thin = new HeldBound("max_prime_gap", "log_sq_window_start",
                1.0, 0.0, 1, 1_800_000L, -135.0);
        assertThat(ConjectureInvestigationWriteBackService.writable(thin)).isFalse();
    }

    @Test
    void notWritable_whenLhsIsAWindowGeometryScalar() {
        // a window-geometry quantity as the LHS is a degenerate bound (triviality
        // filter), never a valid pool relation.
        HeldBound bad = new HeldBound("log_sq_window_start", "max_divisor_count",
                2.0, 0.0, 10, 1_800_000L, -5.0);
        assertThat(ConjectureInvestigationWriteBackService.writable(bad)).isFalse();
    }

    @Test
    void nullTrace_yieldsNoBounds() {
        assertThat(ConjectureInvestigationWriteBackService.parseHeldBounds(null)).isEmpty();
    }
}
