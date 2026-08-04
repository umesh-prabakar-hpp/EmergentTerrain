package com.emergentterrain.llm;

import com.emergentterrain.service.FastModelRegistry;
import com.emergentterrain.service.LlmPool;
import com.emergentterrain.service.ModelHandle;
import com.emergentterrain.service.PrimaryModelRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenTierReporter#snapshot()}. The {@code @Scheduled
 * report()} method is a thin wrapper around {@code snapshot()} that just
 * formats the result into a log line, so the testable surface is the
 * snapshot record.
 */
class TokenTierReporterTest {

    private static ModelHandle handle(String modelId) {
        return new ModelHandle(mock(LlmPool.class), modelId);
    }

    private TokenTierReporter buildReporter(TokenAccountant accountant,
                                             List<String> primaryIds,
                                             List<String> fastIds,
                                             long intervalMs) {
        PrimaryModelRegistry primary = mock(PrimaryModelRegistry.class);
        when(primary.allModels()).thenReturn(primaryIds.stream()
                .map(TokenTierReporterTest::handle).toList());
        FastModelRegistry fast = mock(FastModelRegistry.class);
        when(fast.allModels()).thenReturn(fastIds.stream()
                .map(TokenTierReporterTest::handle).toList());
        return new TokenTierReporter(accountant, primary, fast, intervalMs);
    }

    private static void rec(TokenAccountant a, String provider, String model, long total) {
        a.record(new TokenUsage(provider, model, total / 2, total - total / 2,
                Instant.now()));
    }

    @Test
    void snapshot_splitsTokensByTier() {
        TokenAccountant accountant = new TokenAccountant();
        rec(accountant, "groq",     "llama-3.3-70b-versatile", 5_000);  // primary
        rec(accountant, "groq",     "openai/gpt-oss-120b",     3_000);  // primary
        rec(accountant, "fast","mistral-saba-24b",        1_500);  // fast
        rec(accountant, "cerebras", "unknown-experimental",    400);    // unknown

        TokenTierReporter reporter = buildReporter(accountant,
                List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b"),
                List.of("mistral-saba-24b"),
                300_000L);

        var snap = reporter.snapshot();
        assertThat(snap.primary().tokens()).isEqualTo(8_000);
        assertThat(snap.primary().calls()).isEqualTo(2);
        assertThat(snap.fast().tokens()).isEqualTo(1_500);
        assertThat(snap.fast().calls()).isEqualTo(1);
        assertThat(snap.unknown().tokens()).isEqualTo(400);
        assertThat(snap.unknown().calls()).isEqualTo(1);
        assertThat(snap.total().tokens()).isEqualTo(9_900);
        assertThat(snap.total().calls()).isEqualTo(4);
    }

    @Test
    void snapshot_modelInBothRegistries_classifiedAsPrimary() {
        // Defensive: a model registered in both PRIMARY and FAST registries
        // (operator-error or transition state) should attribute to PRIMARY.
        TokenAccountant accountant = new TokenAccountant();
        rec(accountant, "groq", "llama-3.3-70b-versatile", 2_000);

        TokenTierReporter reporter = buildReporter(accountant,
                List.of("llama-3.3-70b-versatile"),
                List.of("llama-3.3-70b-versatile"),  // same id in both
                300_000L);

        var snap = reporter.snapshot();
        assertThat(snap.primary().tokens()).isEqualTo(2_000);
        assertThat(snap.fast().tokens()).isZero();
    }

    @Test
    void snapshot_adjustmentEntriesLandInUnknown() {
        TokenAccountant accountant = new TokenAccountant();
        accountant.recordDelta("groq", 800);    // pre-debit reservation
        accountant.recordDelta("groq", -250);   // correction

        TokenTierReporter reporter = buildReporter(accountant,
                List.of("llama-3.3-70b-versatile"),
                List.of(),
                300_000L);

        var snap = reporter.snapshot();
        assertThat(snap.primary().tokens()).isZero();
        assertThat(snap.fast().tokens()).isZero();
        // 800 + (-250) = 550 net, with 2 adjustment entries on the deque.
        assertThat(snap.unknown().tokens()).isEqualTo(550);
        assertThat(snap.unknown().calls()).isEqualTo(2);
    }

    @Test
    void snapshot_emptyAccountantReturnsZeros() {
        TokenAccountant accountant = new TokenAccountant();
        TokenTierReporter reporter = buildReporter(accountant,
                List.of("any-primary"),
                List.of("any-fast"),
                300_000L);

        var snap = reporter.snapshot();
        assertThat(snap.primary().tokens()).isZero();
        assertThat(snap.fast().tokens()).isZero();
        assertThat(snap.unknown().tokens()).isZero();
        assertThat(snap.total().tokens()).isZero();
        assertThat(snap.total().calls()).isZero();
    }

    @Test
    void snapshot_absentRegistriesAllowSnapshot_classifiesEverythingUnknown() {
        // When both registries are null (degraded boot, pre-Phase-9 contexts)
        // snapshot() must not NPE — everything routes to unknown.
        TokenAccountant accountant = new TokenAccountant();
        rec(accountant, "groq", "llama-3.3-70b-versatile", 1_000);

        TokenTierReporter reporter = new TokenTierReporter(accountant, null, null, 300_000L);
        var snap = reporter.snapshot();
        assertThat(snap.primary().tokens()).isZero();
        assertThat(snap.fast().tokens()).isZero();
        assertThat(snap.unknown().tokens()).isEqualTo(1_000);
    }
}
