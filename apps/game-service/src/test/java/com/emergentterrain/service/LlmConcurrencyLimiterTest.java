package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Room b72c4e10 throughput fix — pins the two invariants of {@link LlmConcurrencyLimiter}:
 * (A) bounded wait (never blocks indefinitely) and (B) a reserved permit for priority calls so a
 * non-priority flood can't lock them out.
 */
class LlmConcurrencyLimiterTest {

    @Test
    void priorityCallKeepsAReservedSlotWhenNonPriorityIsCapped() {
        // parallelism=2 → 2 main permits, non-priority cap = 1.
        LlmConcurrencyLimiter lim = new LlmConcurrencyLimiter(2, Duration.ofMillis(80));

        LlmConcurrencyLimiter.Permit civic = lim.acquire(false);   // 1 main + 1 cap held
        assertThat(lim.availableSlots()).isEqualTo(1);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(0);

        // A second non-priority call is capped out — it must time out, not consume the last slot.
        assertThatThrownBy(() -> lim.acquire(false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("non-priority cap");

        // ...but a priority (research-pipeline) call still gets the reserved main permit.
        LlmConcurrencyLimiter.Permit research = lim.acquire(true);
        assertThat(lim.availableSlots()).isEqualTo(0);

        research.release();
        civic.release();
        assertThat(lim.availableSlots()).isEqualTo(2);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(1);
    }

    @Test
    void acquireIsBoundedAndTimesOutInsteadOfBlockingForever() {
        LlmConcurrencyLimiter lim = new LlmConcurrencyLimiter(1, Duration.ofMillis(50));
        LlmConcurrencyLimiter.Permit held = lim.acquire(true);   // the only main permit

        // The contended acquire returns (by throwing) instead of blocking — the test completing is
        // itself the proof it did not hang.
        assertThatThrownBy(() -> lim.acquire(true))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timed out acquiring slot");

        held.release();
        assertThat(lim.availableSlots()).isEqualTo(1);
    }

    @Test
    void releaseReturnsBothPermitsForNonPriority() {
        LlmConcurrencyLimiter lim = new LlmConcurrencyLimiter(3, Duration.ofMillis(50));
        assertThat(lim.availableSlots()).isEqualTo(3);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(2);   // reserve 1 of 3

        LlmConcurrencyLimiter.Permit p = lim.acquire(false);
        assertThat(lim.availableSlots()).isEqualTo(2);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(1);
        p.release();
        assertThat(lim.availableSlots()).isEqualTo(3);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(2);
    }

    @Test
    void parallelismOneNeverHasZeroPermits() {
        // Degenerate pool: 1 main permit, cap clamped to 1 (not 0) so non-priority can still run.
        LlmConcurrencyLimiter lim = new LlmConcurrencyLimiter(1, Duration.ofMillis(50));
        assertThat(lim.availableSlots()).isEqualTo(1);
        assertThat(lim.availableNonPriorityPermits()).isEqualTo(1);
    }
}
