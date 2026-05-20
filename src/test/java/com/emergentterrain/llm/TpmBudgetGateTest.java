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
        props.setSoftPauseThresholdPct(85);

        TokenBudgetProperties.Provider groq = new TokenBudgetProperties.Provider();
        groq.setDailyLimit(100_000);
        groq.setMinuteLimit(1_000);   // hard pause at 950 (95%), soft at 850 (85%)
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

    // ── Phase 21 §21.1 — soft / hard split ───────────────────────────────

    @Test
    void softGate_rejectsBetweenSoftAndHardThresholds() {
        // hard=950, soft=850. Current=820 (under soft). Estimate=80 → softTotal=900.
        // 900 > 850 → soft REJECT. But 900 < 950 → hard ACCEPT (single-shot still flows).
        accountant.recordDelta("groq", 820);
        assertThat(gate.tryAcquire("groq", "llama", 80, TpmBudgetGate.GateLevel.SOFT)).isFalse();
        // Reservation NOT recorded on the failed soft check.
        assertThat(accountant.minuteTotal("groq")).isEqualTo(820L);
        // Now the same estimate at HARD passes (820 + 80 = 900 < 950) and reserves.
        assertThat(gate.tryAcquire("groq", "llama", 80, TpmBudgetGate.GateLevel.HARD)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(900L);
    }

    @Test
    void softGate_acceptsBelowSoftThreshold() {
        // Current=400 (well below soft 850). Estimate=300 → 700 < 850 → soft ACCEPT.
        accountant.recordDelta("groq", 400);
        assertThat(gate.tryAcquire("groq", "llama", 300, TpmBudgetGate.GateLevel.SOFT)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(700L);
    }

    @Test
    void hardGate_singleArgFormStillUsesHardThreshold() {
        // current=900 (above soft 850, below hard 950). estimate=40 → 940 < 950 → HARD ACCEPT.
        // The single-arg tryAcquire (back-compat path) must keep using HARD.
        accountant.recordDelta("groq", 900);
        assertThat(gate.tryAcquire("groq", "llama", 40)).isTrue();
        assertThat(accountant.minuteTotal("groq")).isEqualTo(940L);
    }

    @Test
    void tryAcquireOrThrow_softVariant_throwsWithSoftPauseAtInMessage() {
        accountant.recordDelta("groq", 820);
        assertThatExceptionOfType(BudgetExceededException.class)
                .isThrownBy(() -> gate.tryAcquireOrThrow("groq", "llama", 80, TpmBudgetGate.GateLevel.SOFT))
                // pauseAt in the message is the SOFT one (850), not the HARD (950).
                .satisfies(ex -> assertThat(ex.getMessage()).contains("pauseAt=850"));
        assertThat(accountant.minuteTotal("groq")).isEqualTo(820L);
    }

    @Test
    void perProviderOverride_winsOverGlobalDefaults() {
        // Add a second provider with its own soft/hard percentages.
        TokenBudgetProperties.Provider tight = new TokenBudgetProperties.Provider();
        tight.setMinuteLimit(1_000);
        tight.setPauseThresholdPct(99);       // hard at 990, not the global 950
        tight.setSoftPauseThresholdPct(70);   // soft at 700, not the global 850
        props.getProviders().put("tight", tight);

        accountant.recordDelta("tight", 700);
        // 700 + 1 = 701 > 700 → soft REJECT (per-provider soft is tighter).
        assertThat(gate.tryAcquire("tight", "m", 1, TpmBudgetGate.GateLevel.SOFT)).isFalse();
        // 700 + 1 = 701 < 990 → hard ACCEPT (per-provider hard is looser).
        assertThat(gate.tryAcquire("tight", "m", 1, TpmBudgetGate.GateLevel.HARD)).isTrue();
    }

    @Test
    void rejectFiresMetricsHook() {
        // Wire a counting BudgetGateMetrics; verify reject increments it but
        // a successful acquire does not.
        var counts = new java.util.concurrent.atomic.AtomicInteger();
        var upstreamCounts = new java.util.concurrent.atomic.AtomicInteger();
        var hardCounts = new java.util.concurrent.atomic.AtomicInteger();
        BudgetGateMetrics m = new BudgetGateMetrics() {
            @Override public void recordReject(String provider, TpmBudgetGate.GateLevel level) {
                counts.incrementAndGet();
                if (level == TpmBudgetGate.GateLevel.HARD) hardCounts.incrementAndGet();
            }
            @Override public void recordUpstream429(String provider) { upstreamCounts.incrementAndGet(); }
        };
        TpmBudgetGate g = new TpmBudgetGate(accountant, props, m);

        // Success path: no reject.
        g.tryAcquire("groq", "llama", 100);
        assertThat(counts).hasValue(0);

        // Push to a rejection (current=850, plus 200 → 1050 > 950 hard pauseAt).
        accountant.recordDelta("groq", 850);
        g.tryAcquire("groq", "llama", 200);  // HARD reject
        assertThat(counts).hasValue(1);
        assertThat(hardCounts).hasValue(1);

        // SOFT reject increments too, with the SOFT level tag.
        g.tryAcquire("groq", "llama", 50, TpmBudgetGate.GateLevel.SOFT);  // 850+50=900>850 → reject
        assertThat(counts).hasValue(2);
        assertThat(hardCounts).hasValue(1);  // unchanged — second reject was SOFT

        // recordUpstream429 is independent of the gate; unaffected by rejects.
        assertThat(upstreamCounts).hasValue(0);
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
