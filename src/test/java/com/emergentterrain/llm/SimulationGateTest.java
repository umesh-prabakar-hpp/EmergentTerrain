package com.emergentterrain.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the three signals SimulationGate composes — manual disable, the
 * pre-emptive budget check, and hysteresis between pause and resume — so a
 * future refactor can't quietly let scheduled simulation work bleed past the
 * configured cap.
 */
class SimulationGateTest {

    private TokenAccountant accountant;
    private TokenBudgetProperties props;
    private SimulationGate gate;

    @BeforeEach
    void setUp() {
        accountant = new TokenAccountant();
        props = new TokenBudgetProperties();
        props.setEnabled(true);
        props.setPauseThresholdPct(95);
        props.setResumeThresholdPct(80);

        TokenBudgetProperties.Provider groq = new TokenBudgetProperties.Provider();
        groq.setDailyLimit(1000);
        groq.setMinuteLimit(100);
        props.getProviders().put("groq", groq);

        gate = new SimulationGate(props, accountant, null);
    }

    @Test
    void openByDefault_whenNoSpendRecorded() {
        assertThat(gate.isOpen()).isTrue();
    }

    @Test
    void closes_whenDailySpendCrossesPauseThreshold() {
        props.getProviders().get("groq").setMinuteLimit(0); // isolate daily axis
        record("groq", "model-a", 500, 500); // 100% of daily
        assertThat(gate.isOpen()).isFalse();
        assertThat(gate.reason()).contains("groq/day");
    }

    @Test
    void closes_whenMinuteSpendCrossesPauseThreshold() {
        record("groq", "model-a", 50, 50); // 100% of minute (still 10% of daily)
        assertThat(gate.isOpen()).isFalse();
        assertThat(gate.reason()).contains("groq/min");
    }

    @Test
    void aggregatesAcrossAllModelsForSameProvider() {
        props.getProviders().get("groq").setMinuteLimit(0); // isolate daily axis
        // Two Groq models in round-robin together produce 1000 = 100% daily.
        record("groq", "model-a", 300, 300);
        record("groq", "model-b", 200, 200);
        assertThat(gate.isOpen()).isFalse();
    }

    @Test
    void independentProvidersDoNotPoolBudgets() {
        props.getProviders().get("groq").setMinuteLimit(0); // isolate daily axis
        TokenBudgetProperties.Provider anthropic = new TokenBudgetProperties.Provider();
        anthropic.setDailyLimit(1000);
        props.getProviders().put("anthropic", anthropic);

        record("groq", "any", 400, 400);          // 80% of groq daily (under pause 95%)
        record("anthropic", "claude", 100, 100);  // 20% of anthropic daily
        assertThat(gate.isOpen()).isTrue();
    }

    @Test
    void hysteresis_staysClosed_betweenResumeAndPause() {
        // Use a stub accountant so we can drive the gate's view of spend directly.
        TokenAccountant stub = mock(TokenAccountant.class);
        SimulationGate g = new SimulationGate(props, stub, null);

        // Trip pause: 100% of daily.
        when(stub.dailyTotal("groq")).thenReturn(1000L);
        when(stub.minuteTotal("groq")).thenReturn(0L);
        assertThat(g.isOpen()).isFalse();

        // Drop to 85% — above resume (80%), below pause (95%). Must stay closed.
        when(stub.dailyTotal("groq")).thenReturn(850L);
        assertThat(g.isOpen()).isFalse();

        // Drop to 70% — below resume. Reopens.
        when(stub.dailyTotal("groq")).thenReturn(700L);
        assertThat(g.isOpen()).isTrue();
    }

    @Test
    void disabled_alwaysOpen_evenAtOverBudget() {
        props.setEnabled(false);
        record("groq", "m", 5000, 5000); // 1000% of daily
        assertThat(gate.isOpen()).isTrue();
        assertThat(gate.reason()).contains("disabled");
    }

    @Test
    void zeroLimitProvider_isUnmetered() {
        TokenBudgetProperties.Provider ollama = new TokenBudgetProperties.Provider();
        ollama.setDailyLimit(0);
        ollama.setMinuteLimit(0);
        props.getProviders().put("ollama", ollama);

        record("ollama", "gemma3", 1_000_000, 1_000_000);
        assertThat(gate.isOpen()).isTrue();
    }

    private void record(String provider, String model, long prompt, long completion) {
        accountant.record(new TokenUsage(provider, model, prompt, completion, Instant.now()));
    }
}
