package com.emergentterrain.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the arithmetic in {@link TokenBudgetReporter#snapshot(String)}.
 * The scheduler just turns these snapshots into log lines; the testable surface
 * is the snapshot record so we avoid log-scraping.
 */
class TokenBudgetReporterTest {

    private TokenAccountant accountant;
    private TokenBudgetProperties props;
    private TokenBudgetReporter reporter;

    @BeforeEach
    void setUp() {
        accountant = mock(TokenAccountant.class);
        props = new TokenBudgetProperties();
        props.setPauseThresholdPct(95);

        TokenBudgetProperties.Provider groq = new TokenBudgetProperties.Provider();
        groq.setDailyLimit(1000);
        groq.setMinuteLimit(100);
        props.getProviders().put("groq", groq);

        reporter = new TokenBudgetReporter(accountant, props, null, 60_000L);
    }

    @Test
    void idleProvider_hasNoPauseEta() {
        when(accountant.dailyTotal("groq")).thenReturn(0L);
        when(accountant.minuteTotal("groq")).thenReturn(0L);
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        assertThat(s.etaToPauseSec()).isNull();
        assertThat(s.pauseLikely()).isFalse();
        assertThat(s.dailyRemainingBeforePause()).isEqualTo(950); // 95% of 1000
        assertThat(s.minuteRemainingBeforePause()).isEqualTo(95);
    }

    @Test
    void underHorizon_isPauseLikely() {
        // 800 used of 1000 → pause threshold at 950 → 150 remaining
        // Burn rate 600/min → projected eta = 150 / (600/60) = 15s — well under 60min horizon
        when(accountant.dailyTotal("groq")).thenReturn(800L);
        when(accountant.minuteTotal("groq")).thenReturn(60L); // 60 tok/min sustained
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        assertThat(s.dailyRemainingBeforePause()).isEqualTo(150);
        assertThat(s.etaToPauseSec()).isNotNull();
        assertThat(s.etaToPauseSec()).isEqualTo(150L); // ceil(150*60/60) = 150
        assertThat(s.pauseLikely()).isTrue();
    }

    @Test
    void overHorizon_isNotPauseLikely() {
        // 200 used → 750 remaining. Burn 60/min → eta = 750 sec = 12.5min < 60min horizon.
        // So tweak to clearly over: 900 sec horizon limit
        when(accountant.dailyTotal("groq")).thenReturn(100L);
        when(accountant.minuteTotal("groq")).thenReturn(10L); // 10/min slow burn
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        // remaining = 950 - 100 = 850. eta = 850 * 60 / 10 = 5100s = 85min > 60min horizon.
        assertThat(s.dailyRemainingBeforePause()).isEqualTo(850);
        assertThat(s.etaToPauseSec()).isEqualTo(5100L);
        assertThat(s.pauseLikely()).isFalse();
    }

    @Test
    void atOrPastThreshold_pauseLikelyImmediately() {
        when(accountant.dailyTotal("groq")).thenReturn(950L); // exactly at pause threshold
        when(accountant.minuteTotal("groq")).thenReturn(0L);
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        assertThat(s.dailyRemainingBeforePause()).isZero();
        assertThat(s.etaToPauseSec()).isZero();
        assertThat(s.pauseLikely()).isTrue();
    }

    @Test
    void unmeteredProvider_reportsRawTotals_withoutPauseSignal() {
        TokenBudgetProperties.Provider ollama = new TokenBudgetProperties.Provider();
        ollama.setDailyLimit(0);
        ollama.setMinuteLimit(0);
        props.getProviders().put("ollama", ollama);
        when(accountant.dailyTotal("ollama")).thenReturn(123_456L);
        when(accountant.minuteTotal("ollama")).thenReturn(789L);

        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("ollama");
        assertThat(s.dailyLimit()).isZero();
        assertThat(s.dailyRemainingBeforePause()).isEqualTo(-1L); // sentinel for unmetered
        assertThat(s.etaToPauseSec()).isNull();
        assertThat(s.pauseLikely()).isFalse();
    }

    @Test
    void burnRateMatchesMinuteTotal() {
        when(accountant.dailyTotal("groq")).thenReturn(500L);
        when(accountant.minuteTotal("groq")).thenReturn(42L);
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        assertThat(s.burnRatePerMin()).isEqualTo(42L);
    }

    @Test
    void unknownProvider_returnsZeroedSnapshot() {
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("anthropic-not-configured");
        assertThat(s.provider()).isEqualTo("anthropic-not-configured");
        assertThat(s.dailyLimit()).isZero();
        assertThat(s.pauseLikely()).isFalse();
    }

    @Test
    void logFields_carryHumanReadableRemaining() {
        when(accountant.dailyTotal("groq")).thenReturn(500L);
        when(accountant.minuteTotal("groq")).thenReturn(20L);
        TokenBudgetReporter.ProviderSnapshot s = reporter.snapshot("groq");
        String fields = s.toLogFields();
        assertThat(fields).contains("day=500/1000");
        assertThat(fields).contains("dayRemaining=450");
        assertThat(fields).contains("min=20/100");
        assertThat(fields).contains("minRemaining=75");
        assertThat(fields).contains("burnRatePerMin=20");
    }
}
