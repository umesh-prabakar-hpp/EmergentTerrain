package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CerebrasPool's response-parsing helpers.
 *
 * Covers:
 *   • 429 → transient 60s rate-limit block
 *   • LlmPool surface contracts
 */
class CerebrasPoolParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void handleErrorResponse_429NoHeaderBlocksUntilNextMinuteBoundary() {
        // Without a Retry-After header, the block runs until the top of the next
        // minute — never more than 60s, often less.
        CerebrasPool pool = buildPool();
        long before = System.currentTimeMillis();

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429, emptyHeaders(),
                "{\"message\":\"Requests per minute limit exceeded\","
                        + "\"type\":\"too_many_requests_error\","
                        + "\"code\":\"request_quota_exceeded\"}");

        long blockMs = blockMsFor(pool, "qwen-3-235b-a22b-instruct-2507", before);
        // Block at most 60s plus 0.5s slack. Average ~30s; never zero.
        assertThat(blockMs).isBetween(500L, 61_000L);
    }

    @Test
    void f_handleErrorResponse_429HonorsRetryAfterHeader() {
        // Header says wait 12s — block reflects that, not the next-minute fallback.
        CerebrasPool pool = buildPool();
        long before = System.currentTimeMillis();

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429,
                headersOf("retry-after", "12"), "rate limit");

        assertThat(blockMsFor(pool, "qwen-3-235b-a22b-instruct-2507", before))
                .isBetween(11_000L, 13_000L);
    }

    @Test
    void f_handleErrorResponse_429HonorsXRateLimitResetHeader() {
        // x-ratelimit-reset in epoch-seconds → block is the delta to that instant.
        CerebrasPool pool = buildPool();
        long before = System.currentTimeMillis();
        long resetEpochSec = (before / 1000) + 8;  // 8s in the future

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429,
                headersOf("x-ratelimit-reset", String.valueOf(resetEpochSec)),
                "rate limit");

        assertThat(blockMsFor(pool, "qwen-3-235b-a22b-instruct-2507", before))
                .isBetween(7_000L, 9_000L);
    }

    @Test
    void handleErrorResponse_otherErrorsDoNotBlock() {
        CerebrasPool pool = buildPool();
        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 500, emptyHeaders(), "Internal error");
        assertThat(pool.allBlocked()).isFalse();
    }

    @Test
    void allBlocked_correctlyAggregatesAcrossSingleModel() {
        CerebrasPool pool = buildPool();
        assertThat(pool.allBlocked()).isFalse();

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429, emptyHeaders(), "rate limit");
        assertThat(pool.allBlocked()).isTrue();
    }

    private static java.net.http.HttpHeaders emptyHeaders() {
        return java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true);
    }

    private static java.net.http.HttpHeaders headersOf(String name, String value) {
        return java.net.http.HttpHeaders.of(
                java.util.Map.of(name, java.util.List.of(value)),
                (k, v) -> true);
    }

    private static long blockMsFor(CerebrasPool pool, String modelId, long baseMs) {
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        Long blockUntil = expiry.get(modelId);
        assertThat(blockUntil).isNotNull();
        return blockUntil - baseMs;
    }

    @Test
    void poolExposesCorrectProviderKeyAndCapabilities() {
        CerebrasPool pool = buildPool();
        assertThat(pool.providerKey()).isEqualTo("cerebras");
        assertThat(pool.supportsTools()).isTrue();
        assertThat(pool.selectedModel()).isEqualTo("qwen-3-235b-a22b-instruct-2507");
        assertThat(pool.allBlocked()).isFalse();
        assertThat(pool.role()).isEqualTo("fast-audit");
    }

    @Test
    void roleEmpty_whenConfiguredBlank() {
        CerebrasPool pool = new CerebrasPool(
                "csk-test-key", "https://api.cerebras.ai/v1",
                2000, 1500, 2,
                "qwen-3-235b-a22b-instruct-2507",
                "", mapper);
        assertThat(pool.role()).isEmpty();
    }

    private CerebrasPool buildPool() {
        return new CerebrasPool(
                "csk-test-key",
                "https://api.cerebras.ai/v1",
                2000, 1500, 2,
                "qwen-3-235b-a22b-instruct-2507",
                "fast-audit",
                mapper);
    }
}
