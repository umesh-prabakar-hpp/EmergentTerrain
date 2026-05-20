package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GenericOpenAICompatPool#parseRetryAfterMs} covering the
 * four error-body shapes the parser is expected to handle plus the fallback /
 * floor behaviour. Driven by 2026-05-20 corpus evidence in
 * {@code logs/et-app.log}: Groq's {@code "269.999999ms"} TPM-window form was
 * silently falling through to {@code INITIAL_BACKOFF_MS} (10s) because the
 * prior parser only recognised {@code s}-suffixed seconds.
 */
class GenericOpenAICompatPoolParseTest {

    @Test
    void parses_groq_subsecond_ms_form() {
        String body = "{\"error\":{\"message\":\"Rate limit reached ... "
                + "Please try again in 269.999999ms. Need more tokens?\"}}";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 269.999999ms → 369ms (+100ms safety), then floored to 200ms minimum.
        // Actual: 269 + 100 = 369ms; above the 200ms floor → unchanged.
        assertThat(ms).hasValue(369L);
    }

    @Test
    void parses_groq_seconds_form() {
        String body = "Please try again in 4.82s.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 4820ms + 500ms pad
        assertThat(ms).hasValue(5320L);
    }

    @Test
    void parses_groq_minutes_and_seconds_form() {
        String body = "Please try again in 22m45.12s.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 22*60_000 + 45120 + 500 = 1_365_620ms
        assertThat(ms).hasValue(1_365_620L);
    }

    @Test
    void parses_groq_hours_minutes_seconds_form() {
        String body = "Please try again in 1h22m35.04s.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 3_600_000 + 22*60_000 + 35040 + 500 = 4_955_540ms
        assertThat(ms).hasValue(4_955_540L);
    }

    @Test
    void parses_bare_seconds_anywhere() {
        // No "try again in" preamble — Groq sometimes omits it.
        String body = "tier on_demand on tokens per minute (TPM) ... 6.5s window";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 6500 + 500 = 7000ms
        assertThat(ms).hasValue(7000L);
    }

    @Test
    void floors_subsecond_ms_below_200ms() {
        String body = "Please try again in 50ms.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // 50 + 100 = 150ms, below 200ms floor → bumped to 200ms.
        assertThat(ms).hasValue(200L);
    }

    @Test
    void returns_empty_for_terse_nvidia_body() {
        // NVIDIA's literal 429 body — no timing info, no parseable form.
        String body = "{\"status\":429,\"title\":\"Too Many Requests\"}";
        assertThat(GenericOpenAICompatPool.parseRetryAfterMs(body)).isEmpty();
    }

    @Test
    void returns_empty_for_null_message() {
        assertThat(GenericOpenAICompatPool.parseRetryAfterMs(null)).isEmpty();
    }

    @Test
    void ms_matcher_does_not_eat_seconds_form() {
        // Regression guard: the new ms-first matcher must not capture the
        // "0s" or similar fragments from a seconds-form body.
        String body = "Please try again in 4.82s.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // Must be the seconds value (5320ms), not some sub-second misparse.
        assertThat(ms).hasValue(5320L);
    }

    @Test
    void rejects_nonsense_huge_ms_value() {
        // Sanity bound: 600s = 600_000ms ceiling on the ms-form matcher so a
        // mis-formatted "9999999ms" doesn't yield a 2.8h block.
        String body = "Please try again in 9999999ms.";
        OptionalLong ms = GenericOpenAICompatPool.parseRetryAfterMs(body);
        // Falls through to seconds matcher → no match → empty.
        assertThat(ms).isEmpty();
    }
}
