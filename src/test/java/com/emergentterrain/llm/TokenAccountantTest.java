package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenAccountant} — focused on the predicate-driven
 * windowed reads added 2026-05-20 to support tier-aware reporting in
 * {@link TokenTierReporter}.
 *
 * <p>The pre-existing aggregate getters (dailyTotal / minuteTotal /
 * minuteTotalAll) are exercised indirectly via the existing
 * {@code TokenBudgetReporterTest} and don't need duplicate coverage here.
 */
class TokenAccountantTest {

    private final TokenAccountant accountant = new TokenAccountant();

    private void rec(String provider, String model, long total) {
        accountant.record(new TokenUsage(provider, model, total / 2, total - total / 2,
                Instant.now()));
    }

    @Test
    void tokensByModel_matchesPredicate() {
        rec("groq", "llama-3.3-70b", 1_000);
        rec("groq", "gpt-oss-120b",  2_000);
        rec("cerebras", "qwen-3-235b", 3_000);

        Predicate<String> isLlama = m -> m != null && m.startsWith("llama");
        long llamaOnly = accountant.tokensByModel(60_000L, isLlama);
        assertThat(llamaOnly).isEqualTo(1_000);

        Predicate<String> isAny = m -> m != null;
        assertThat(accountant.tokensByModel(60_000L, isAny)).isEqualTo(6_000);
    }

    @Test
    void callsByModel_countsEntries() {
        rec("groq", "llama-3.3-70b", 500);
        rec("groq", "llama-3.3-70b", 700);
        rec("groq", "gpt-oss-120b",  900);

        Predicate<String> isLlama = m -> "llama-3.3-70b".equals(m);
        assertThat(accountant.callsByModel(60_000L, isLlama)).isEqualTo(2);
        assertThat(accountant.callsByModel(60_000L, m -> m != null)).isEqualTo(3);
    }

    @Test
    void tokensByModel_excludesAdjustmentEntriesWhenPredicateRejectsNull() {
        // Predicate that rejects null model — adjustment entries from
        // recordDelta (no model) should NOT contribute to the sum.
        rec("groq", "llama-3.3-70b", 1_000);
        accountant.recordDelta("groq", 500);  // pre-debit reservation

        Predicate<String> isLlama = m -> "llama-3.3-70b".equals(m);
        assertThat(accountant.tokensByModel(60_000L, isLlama)).isEqualTo(1_000);
        // Predicate that accepts null sees the adjustment too.
        Predicate<String> isUnknown = m -> m == null;
        assertThat(accountant.tokensByModel(60_000L, isUnknown)).isEqualTo(500);
    }

    @Test
    void tokensByModel_respectsWindowBound() {
        // Record an entry, then verify a window shorter than its age excludes it.
        // We can't easily fast-forward the clock, but we can pass windowMs=0,
        // which means "include only entries from this exact millisecond and later"
        // — at typical clock resolution, the just-recorded entry's timestamp
        // is in the past relative to System.currentTimeMillis() at the
        // tokensByModel call, so it should be excluded.
        rec("groq", "any-model", 1_000);
        try {
            Thread.sleep(2);  // ensure cutoff > entry.t
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        assertThat(accountant.tokensByModel(0L, m -> true)).isZero();
        // The same entry is still within a 60s window.
        assertThat(accountant.tokensByModel(60_000L, m -> true)).isEqualTo(1_000);
    }

    @Test
    void tokensByModel_emptyAccountantReturnsZero() {
        assertThat(accountant.tokensByModel(60_000L, m -> true)).isZero();
        assertThat(accountant.callsByModel(60_000L, m -> true)).isZero();
    }

    @Test
    void recordDelta_storesNullModel_classifiedAsUnknownByTierPredicate() {
        // Reproduces the TpmBudgetGate pre-debit reservation path: gate
        // reserves 800 tokens before the HTTP call; the actual call later
        // records 550. Net = 800 (delta) - 250 (correction) = 550 effective.
        accountant.recordDelta("groq", 800);
        accountant.recordDelta("groq", -250);

        Predicate<String> isPrimary = m -> "llama-3.3-70b".equals(m);
        Predicate<String> isUnknown = m -> m == null;
        assertThat(accountant.tokensByModel(60_000L, isPrimary)).isZero();
        assertThat(accountant.tokensByModel(60_000L, isUnknown)).isEqualTo(550);
    }
}
