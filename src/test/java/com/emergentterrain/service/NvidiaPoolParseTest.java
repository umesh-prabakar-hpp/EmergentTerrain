package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NvidiaPool's response-parsing helpers.
 *
 * Covers:
 *   • 404 "Function not found for account" → permanent paid-tier disable
 *   • 429 → transient 60s rate-limit block
 *   • selectedModel and capabilities surface
 */
class NvidiaPoolParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void handleErrorResponse_paidTierModelGetsPermanentlyDisabled() {
        NvidiaPool pool = buildPool();
        String body = "{\"status\":404,\"title\":\"Not Found\","
                + "\"detail\":\"Function 'b0fcd392-e905-4ab4-8eb9-aeae95c30b37': "
                + "Not found for account 'SSBQR1S_ZQlxQ5ZHExV8jzx6vVIpmx3KAjQYNgJ1gf4'\"}";

        pool.handleErrorResponse("nvidia/nemotron-4-340b-instruct", 404, body);

        // Permanently disabled — survives indefinitely (not bound to a timestamp)
        assertThat(pool.isModelUnavailable("nvidia/nemotron-4-340b-instruct")).isTrue();
        assertThat(pool.isModelUnavailable("meta/llama-3.3-70b-instruct")).isFalse();
    }

    @Test
    void handleErrorResponse_404WithoutPaidTierSignature_doesNothing() {
        NvidiaPool pool = buildPool();
        String body = "{\"status\":404,\"detail\":\"Model not found\"}";

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 404, body);

        assertThat(pool.isModelUnavailable("meta/llama-3.3-70b-instruct")).isFalse();
    }

    @Test
    void handleErrorResponse_429TransientBlock60s() {
        NvidiaPool pool = buildPool();
        long before = System.currentTimeMillis();

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 429, "rate limit");

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        Long blockUntil = expiry.get("meta/llama-3.3-70b-instruct");
        assertThat(blockUntil).isNotNull();
        assertThat(blockUntil - before).isBetween(59_000L, 61_000L);
    }

    @Test
    void allBlocked_returnsTrueWhenAllModelsArePaidTier() {
        NvidiaPool pool = buildPool();
        String body = "Function 'x': Not found for account 'y'";
        for (String m : pool.selectedModels()) {
            pool.handleErrorResponse(m, 404, body);
        }
        assertThat(pool.allBlocked()).isTrue();
    }

    @Test
    void poolExposesCorrectProviderKeyAndCapabilities() {
        NvidiaPool pool = buildPool();
        assertThat(pool.providerKey()).isEqualTo("nvidia");
        assertThat(pool.supportsTools()).isTrue();
        assertThat(pool.selectedModel()).isEqualTo("meta/llama-3.3-70b-instruct");
        assertThat(pool.allBlocked()).isFalse();
    }

    private NvidiaPool buildPool() {
        return new NvidiaPool(
                "nvapi-test-key",
                "https://integrate.api.nvidia.com/v1",
                2000, 1500, 3,
                "meta/llama-3.3-70b-instruct,openai/gpt-oss-120b,nvidia/nemotron-3-super-120b-a12b",
                mapper);
    }
}
