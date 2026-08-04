package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 follow-on — coverage of the provider-agnostic rate-limit header parser.
 * Each test pins one header form so the three new pool handlers can rely on the
 * contract.
 */
class RateLimitParserTest {

    @Test
    void retryAfterIntegerSecondsParsedToMs() {
        HttpHeaders h = headers("Retry-After", "30");
        assertThat(RateLimitParser.inferRetryAfterMs(h))
                .isEqualTo(OptionalLong.of(30_000L));
    }

    @Test
    void retryAfterFractionalSecondsParsedToMs() {
        HttpHeaders h = headers("retry-after", "2.5");
        assertThat(RateLimitParser.inferRetryAfterMs(h))
                .isEqualTo(OptionalLong.of(2_500L));
    }

    @Test
    void retryAfterHttpDateParsedToFutureDelta() {
        // 90s in the future.
        Instant target = Instant.now().plusSeconds(90);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(target, ZoneId.of("GMT")));
        HttpHeaders h = headers("Retry-After", httpDate);
        OptionalLong ms = RateLimitParser.inferRetryAfterMs(h);
        assertThat(ms).isPresent();
        // Allow a few seconds of slack for clock drift / test timing.
        assertThat(ms.getAsLong()).isBetween(85_000L, 95_000L);
    }

    @Test
    void xRateLimitResetEpochSecondsParsed() {
        long resetEpochSec = (System.currentTimeMillis() / 1000) + 45;
        HttpHeaders h = headers("x-ratelimit-reset", String.valueOf(resetEpochSec));
        OptionalLong ms = RateLimitParser.inferRetryAfterMs(h);
        assertThat(ms).isPresent();
        assertThat(ms.getAsLong()).isBetween(44_000L, 46_000L);
    }

    @Test
    void xRateLimitResetEpochMillisAlsoParsedCorrectly() {
        // Some shims send Unix-millis instead of Unix-seconds — distinguished by
        // the magnitude (> 1e12 means millis, < 1e12 means seconds).
        long resetEpochMs = System.currentTimeMillis() + 8_000;
        HttpHeaders h = headers("x-ratelimit-reset", String.valueOf(resetEpochMs));
        OptionalLong ms = RateLimitParser.inferRetryAfterMs(h);
        assertThat(ms).isPresent();
        assertThat(ms.getAsLong()).isBetween(7_000L, 9_000L);
    }

    @Test
    void xRateLimitResetAfterRelativeSecondsParsed() {
        HttpHeaders h = headers("x-ratelimit-reset-after", "3.2");
        assertThat(RateLimitParser.inferRetryAfterMs(h))
                .isEqualTo(OptionalLong.of(3_200L));
    }

    @Test
    void retryAfterPreferredOverXRateLimitReset() {
        // Retry-After comes first in the read order even when both are present.
        long resetEpochSec = (System.currentTimeMillis() / 1000) + 60;
        HttpHeaders h = HttpHeaders.of(Map.of(
                "Retry-After", List.of("10"),
                "x-ratelimit-reset", List.of(String.valueOf(resetEpochSec))), (k, v) -> true);
        OptionalLong ms = RateLimitParser.inferRetryAfterMs(h);
        assertThat(ms).isEqualTo(OptionalLong.of(10_000L));
    }

    @Test
    void emptyHeadersReturnEmpty() {
        HttpHeaders h = HttpHeaders.of(Map.of(), (k, v) -> true);
        assertThat(RateLimitParser.inferRetryAfterMs(h)).isEmpty();
    }

    @Test
    void nullHeadersReturnEmpty() {
        assertThat(RateLimitParser.inferRetryAfterMs(null)).isEmpty();
    }

    @Test
    void garbageRetryAfterReturnsEmpty() {
        HttpHeaders h = headers("Retry-After", "not a number or date");
        assertThat(RateLimitParser.inferRetryAfterMs(h)).isEmpty();
    }

    @Test
    void negativeOrPastResetReturnsEmpty() {
        // x-ratelimit-reset pointing to the past should not produce a negative window.
        long pastEpochSec = (System.currentTimeMillis() / 1000) - 30;
        HttpHeaders h = headers("x-ratelimit-reset", String.valueOf(pastEpochSec));
        assertThat(RateLimitParser.inferRetryAfterMs(h)).isEmpty();
    }

    @Test
    void zeroSecondsReturnsEmpty() {
        // A spec-compliant "0" means "you can retry immediately" — we treat that
        // as no actionable info and fall through to provider-specific fallback.
        HttpHeaders h = headers("Retry-After", "0");
        assertThat(RateLimitParser.inferRetryAfterMs(h)).isEmpty();
    }

    private static HttpHeaders headers(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (k, v) -> true);
    }
}
