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
    void handleErrorResponse_429TransientBlock60s() {
        CerebrasPool pool = buildPool();
        long before = System.currentTimeMillis();

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429,
                "{\"message\":\"Requests per minute limit exceeded\","
                        + "\"type\":\"too_many_requests_error\","
                        + "\"code\":\"request_quota_exceeded\"}");

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        Long blockUntil = expiry.get("qwen-3-235b-a22b-instruct-2507");
        assertThat(blockUntil).isNotNull();
        assertThat(blockUntil - before).isBetween(59_000L, 61_000L);
    }

    @Test
    void handleErrorResponse_otherErrorsDoNotBlock() {
        CerebrasPool pool = buildPool();
        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 500, "Internal error");
        assertThat(pool.allBlocked()).isFalse();
    }

    @Test
    void allBlocked_correctlyAggregatesAcrossSingleModel() {
        CerebrasPool pool = buildPool();
        assertThat(pool.allBlocked()).isFalse();

        pool.handleErrorResponse("qwen-3-235b-a22b-instruct-2507", 429, "rate limit");
        assertThat(pool.allBlocked()).isTrue();
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
