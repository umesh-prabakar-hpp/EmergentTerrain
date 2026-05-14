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

        pool.handleErrorResponse("nvidia/nemotron-4-340b-instruct", 404, emptyHeaders(), body);

        // Permanently disabled — survives indefinitely (not bound to a timestamp)
        assertThat(pool.isModelUnavailable("nvidia/nemotron-4-340b-instruct")).isTrue();
        assertThat(pool.isModelUnavailable("meta/llama-3.3-70b-instruct")).isFalse();
    }

    @Test
    void handleErrorResponse_404WithoutPaidTierSignature_doesNothing() {
        NvidiaPool pool = buildPool();
        String body = "{\"status\":404,\"detail\":\"Model not found\"}";

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 404, emptyHeaders(), body);

        assertThat(pool.isModelUnavailable("meta/llama-3.3-70b-instruct")).isFalse();
    }

    @Test
    void handleErrorResponse_429NoHeaderBlocksFor60s() {
        // No Retry-After header + "rate limit" body → fall back to 60s default.
        NvidiaPool pool = buildPool();
        long before = System.currentTimeMillis();

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 429, emptyHeaders(), "rate limit");

        assertThat(blockMsFor(pool, "meta/llama-3.3-70b-instruct", before))
                .isBetween(59_000L, 61_000L);
    }

    @Test
    void f_handleErrorResponse_429HonorsRetryAfterHeader() {
        // Header says wait 5s — block reflects that, not the 60s fallback.
        NvidiaPool pool = buildPool();
        long before = System.currentTimeMillis();
        java.net.http.HttpHeaders headers = headersOf("Retry-After", "5");

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 429, headers, "rate limit");

        assertThat(blockMsFor(pool, "meta/llama-3.3-70b-instruct", before))
                .isBetween(4_000L, 6_000L);
    }

    @Test
    void f_handleErrorResponse_429MonthlyCreditExhaustionBlocksFor24h() {
        // Body language indicating monthly credit exhaustion → 24h block, not 60s.
        NvidiaPool pool = buildPool();
        long before = System.currentTimeMillis();
        String body = "{\"detail\":\"Monthly credit usage limit reached for this account\"}";

        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 429, emptyHeaders(), body);

        long expected24h = 24L * 60 * 60 * 1000;
        assertThat(blockMsFor(pool, "meta/llama-3.3-70b-instruct", before))
                .isBetween(expected24h - 5_000, expected24h + 5_000);
    }

    @Test
    void allBlocked_returnsTrueWhenAllModelsArePaidTier() {
        NvidiaPool pool = buildPool();
        String body = "Function 'x': Not found for account 'y'";
        for (String m : pool.selectedModels()) {
            pool.handleErrorResponse(m, 404, emptyHeaders(), body);
        }
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

    @Test
    void u1_modelIdsReturnsActiveModelsList() {
        NvidiaPool pool = buildPool();
        assertThat(pool.modelIds())
                .contains("meta/llama-3.3-70b-instruct",
                          "openai/gpt-oss-120b",
                          "nvidia/nemotron-3-super-120b-a12b");
    }

    @Test
    void u1_isModelBlockedReflectsRateLimitState() {
        NvidiaPool pool = buildPool();
        assertThat(pool.isModelBlocked("meta/llama-3.3-70b-instruct")).isFalse();
        pool.handleErrorResponse("meta/llama-3.3-70b-instruct", 429, emptyHeaders(), "rate limit");
        assertThat(pool.isModelBlocked("meta/llama-3.3-70b-instruct")).isTrue();
        // Other models unaffected.
        assertThat(pool.isModelBlocked("openai/gpt-oss-120b")).isFalse();
    }

    @Test
    void u1_isModelBlockedTrueForPaidTierModels() {
        NvidiaPool pool = buildPool();
        String body = "Function 'x': Not found for account 'y'";
        pool.handleErrorResponse("nvidia/nemotron-3-super-120b-a12b", 404, emptyHeaders(), body);
        // Paid-tier blocks count as "blocked" for the registry's purpose.
        assertThat(pool.isModelBlocked("nvidia/nemotron-3-super-120b-a12b")).isTrue();
    }

    @Test
    void u1_callSyncOnModelRejectsUnknownModel() {
        NvidiaPool pool = buildPool();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                pool.callSyncOnModel("nonexistent/model", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent/model");
    }

    private static long blockMsFor(NvidiaPool pool, String modelId, long baseMs) {
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
