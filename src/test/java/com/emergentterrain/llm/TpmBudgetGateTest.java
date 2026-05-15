package com.emergentterrain.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pins the pre-debit semantics of {@link TpmBudgetGate} — concurrent callers
 * must see one another's reservations so the per-minute budget cannot be
 * collectively overshot (the bug observed at 172% in the research/a3289005
 * logs).
 */
class TpmBudgetGateTest {

    private TokenAccountant accountant;
    private TokenBudgetProperties props;
    private TpmBudgetGate gate;

    @BeforeEach
    void setUp() {
        accountant = new TokenAccountant();
        props = new TokenBudgetProperties();
        props.setPauseThresholdPct(95);

        TokenBudgetProperties.Provider groq = new TokenBudgetProperties.Provider();
        groq.setDailyLimit(100_000);
        groq.setMinuteLimit(1_000);   // pause at 950 (= 95%)
        props.getProviders().put("groq", groq);

        TokenBudgetProperties.Provider ollama = new TokenBudgetProperties.Provider();
        ollama.setDailyLimit(0);
        ollama.setMinuteLimit(0);     // unmetered
        props.getProviders().put("ollama", ollama);

        gate = new TpmBudgetGate(accountant, props);
    }

    @Test
    void belowBudget_acquiresAndPreRecordsEstimate() {
        assertThat(gate.tryAcquire("groq", "llama", 300)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(300L);
    }

    @Test
    void exceedingBudget_rejectsWithoutRecording() {
        // Pre-fill to 800. pauseAt = 950. Trying to add 200 → 1000 > 950 → reject.
        accountant.recordDelta("groq", 800);
        assertThat(gate.tryAcquire("groq", "llama", 200)).isFalse();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(800L);  // unchanged
    }

    @Test
    void exactlyAtBudget_accepts() {
        // pauseAt = 950. current = 700. estimate = 250. current + estimate = 950.
        // 950 > 950 is false (it's exactly at the threshold, not over), so accept.
        accountant.recordDelta("groq", 700);
        assertThat(gate.tryAcquire("groq", "llama", 250)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(950L);
    }

    @Test
    void secondCaller_seesFirstReservation() {
        // The whole point of pre-debit: serial calls must agree on the budget
        // even before the HTTP responses come back.
        assertThat(gate.tryAcquire("groq", "llama", 600)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(600L);

        // Second call: 600 + 400 = 1000 > 950 → reject.
        assertThat(gate.tryAcquire("groq", "llama", 400)).isFalse();
        // Second call WOULD fit at 350: 600 + 350 = 950 (exactly at threshold) → accept.
        assertThat(gate.tryAcquire("groq", "llama", 350)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(950L);
    }

    @Test
    void unmeteredProvider_alwaysAcceptsAndDoesNotRecord() {
        assertThat(gate.tryAcquire("ollama", "gemma3:4b", 5_000_000)).isTrue();
        assertThat(accountant.minuteTotal("ollama")).isZero();
    }

    @Test
    void unknownProvider_alwaysAccepts() {
        // No matching entry in props → behave as unmetered (passthrough).
        assertThat(gate.tryAcquire("anthropic-not-configured", "claude", 1_000_000)).isTrue();
        assertThat(accountant.minuteTotal("anthropic-not-configured")).isZero();
    }

    @Test
    void zeroOrNegativeEstimate_trivialAccept() {
        assertThat(gate.tryAcquire("groq", "llama", 0)).isTrue();
        assertThat(gate.tryAcquire("groq", "llama", -50)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isZero();
    }

    @Test
    void correct_appliesDelta() {
        // Reserve 800, response says actual 550 → delta = -250.
        assertThat(gate.tryAcquire("groq", "llama", 800)).isTrue();
        gate.correct("groq", "llama", 800, 550);
        assertThat(accountant.minuteTotal("groq")).isEqualTo(550L);
    }

    @Test
    void correct_underestimateAddsPositiveDelta() {
        // Reserve 300, response says actual 450 → delta = +150.
        assertThat(gate.tryAcquire("groq", "llama", 300)).isTrue();
        gate.correct("groq", "llama", 300, 450);
        assertThat(accountant.minuteTotal("groq")).isEqualTo(450L);
    }

    @Test
    void correct_zeroDelta_isNoop() {
        gate.tryAcquire("groq", "llama", 400);
        gate.correct("groq", "llama", 400, 400);
        assertThat(accountant.minuteTotal("groq")).isEqualTo(400L);
    }

    @Test
    void correct_unmeteredProvider_isNoop() {
        gate.correct("ollama", "gemma3:4b", 800, 550);
        assertThat(accountant.minuteTotal("ollama")).isZero();
    }

    @Test
    void tryAcquireOrThrow_throwsWhenOverBudget() {
        accountant.recordDelta("groq", 800);
        assertThatExceptionOfType(BudgetExceededException.class)
                .isThrownBy(() -> gate.tryAcquireOrThrow("groq", "llama", 200))
                .satisfies(ex -> {
                    assertThat(ex.provider()).isEqualTo("groq");
                    assertThat(ex.estimate()).isEqualTo(200L);
                    assertThat(ex.getMessage()).contains("current=800").contains("pauseAt=950");
                });
        // Reservation NOT recorded on throw.
        assertThat(accountant.minuteTotal("groq")).isEqualTo(800L);
    }

    @Test
    void tryAcquireOrThrow_succeedsWhenUnderBudget() {
        gate.tryAcquireOrThrow("groq", "llama", 500);
        assertThat(accountant.minuteTotal("groq")).isEqualTo(500L);
    }

    @Test
    void simulatedConcurrentBurst_doesNotOvershoot() throws InterruptedException {
        // Simulates the research/a3289005 bug condition: many parallel virtual
        // threads each trying to acquire. With synchronization, the SUM of
        // accepted reservations must never exceed pauseAt (950).
        final int threads = 16;
        final long estimatePerThread = 100;
        Thread[] workers = new Thread[threads];
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                if (gate.tryAcquire("groq", "llama", estimatePerThread)) {
                    accepted.incrementAndGet();
                }
            });
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        // Accept count × estimate must be <= pauseAt (950). With 100/thread,
        // at most 9 accepts (900 tokens) — the 10th would push to 1000 > 950.
        assertThat(accepted.get()).isLessThanOrEqualTo(9);
        assertThat(accountant.minuteTotal("groq")).isLessThanOrEqualTo(950L);
        assertThat(accountant.minuteTotal("groq")).isEqualTo(accepted.get() * estimatePerThread);
    }
}
