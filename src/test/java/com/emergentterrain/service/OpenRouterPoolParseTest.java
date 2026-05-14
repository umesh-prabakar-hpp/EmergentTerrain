package com.emergentterrain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OpenRouterPool's response-parsing helpers — the parts of the pool
 * that can be exercised without hitting the network.
 *
 * Covers:
 *   • retry-after extraction from the OpenRouter 429 error shape
 *   • Venice (and similar excluded-backend) blocking via handleErrorResponse + checkBackendExclusion
 */
class OpenRouterPoolParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseRetryAfterMs_extractsSecondsFromRateLimitJson() {
        String body = "{\"error\":{\"code\":429,\"metadata\":{\"retry_after_seconds\":13,"
                + "\"provider_name\":\"Venice\"}}}";
        OptionalLong ms = OpenRouterPool.parseRetryAfterMs(body);
        assertThat(ms).hasValue(13_500L); // 13s + 500ms safety pad
    }

    @Test
    void parseRetryAfterMs_extractsFractionalSeconds() {
        String body = "{\"error\":{\"metadata\":{\"retry_after_seconds\":2.116,"
                + "\"provider_name\":\"Venice\"}}}";
        OptionalLong ms = OpenRouterPool.parseRetryAfterMs(body);
        assertThat(ms).hasValue(2_616L); // 2.116s -> 2116ms + 500ms pad
    }

    @Test
    void parseRetryAfterMs_emptyWhenNoMatch() {
        assertThat(OpenRouterPool.parseRetryAfterMs("plain rate limit message")).isEmpty();
        assertThat(OpenRouterPool.parseRetryAfterMs("")).isEmpty();
    }

    @Test
    void handleErrorResponse_blocksExcludedBackendForFiveMinutes() throws Exception {
        OpenRouterPool pool = buildPool("Venice");
        String body = "{\"error\":{\"code\":429,\"metadata\":{\"retry_after_seconds\":3,"
                + "\"provider_name\":\"Venice\"}}}";

        long before = System.currentTimeMillis();
        pool.handleErrorResponse("meta-llama/llama-3.3-70b-instruct:free", 429, emptyHeaders(), body);
        long after = System.currentTimeMillis();

        Long blockUntil = blockExpiryFor(pool, "meta-llama/llama-3.3-70b-instruct:free");
        assertThat(blockUntil).isNotNull();
        // Excluded-backend block is at least 5 minutes (300_000 ms) regardless of retry_after.
        assertThat(blockUntil - before).isGreaterThanOrEqualTo(5 * 60 * 1000L);
        assertThat(blockUntil - after).isGreaterThanOrEqualTo(5 * 60 * 1000L - 200);
    }

    @Test
    void handleErrorResponse_usesRetryAfterForNonExcludedBackend() throws Exception {
        OpenRouterPool pool = buildPool("Venice");
        String body = "{\"error\":{\"code\":429,\"metadata\":{\"retry_after_seconds\":15,"
                + "\"provider_name\":\"OpenInference\"}}}";

        long before = System.currentTimeMillis();
        pool.handleErrorResponse("openai/gpt-oss-120b:free", 429, emptyHeaders(), body);

        Long blockUntil = blockExpiryFor(pool, "openai/gpt-oss-120b:free");
        assertThat(blockUntil).isNotNull();
        // OpenInference is NOT excluded — should use retry_after_seconds * 1000 (15_000ms).
        long delta = blockUntil - before;
        assertThat(delta).isBetween(15_000L, 16_000L);
    }

    @Test
    void f_handleErrorResponse_headerWindowOverridesShorterBodyWindow() throws Exception {
        // Body says 5s, header says 30s — the longer window wins (header).
        OpenRouterPool pool = buildPool("Venice");
        String body = "{\"error\":{\"code\":429,\"metadata\":{\"retry_after_seconds\":5,"
                + "\"provider_name\":\"OpenInference\"}}}";
        java.net.http.HttpHeaders headers = headersOf("retry-after", "30");

        long before = System.currentTimeMillis();
        pool.handleErrorResponse("openai/gpt-oss-120b:free", 429, headers, body);

        Long blockUntil = blockExpiryFor(pool, "openai/gpt-oss-120b:free");
        assertThat(blockUntil - before).isBetween(29_000L, 31_000L);
    }

    @Test
    void f_handleErrorResponse_usesHeaderWhenBodyEnvelopeMissing() throws Exception {
        // No body envelope at all — header alone drives the block.
        OpenRouterPool pool = buildPool("Venice");
        java.net.http.HttpHeaders headers = headersOf("retry-after", "7");

        long before = System.currentTimeMillis();
        pool.handleErrorResponse("openai/gpt-oss-120b:free", 429, headers, "{}");

        Long blockUntil = blockExpiryFor(pool, "openai/gpt-oss-120b:free");
        assertThat(blockUntil - before).isBetween(6_000L, 8_000L);
    }

    private static java.net.http.HttpHeaders emptyHeaders() {
        return java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true);
    }

    private static java.net.http.HttpHeaders headersOf(String name, String value) {
        return java.net.http.HttpHeaders.of(
                java.util.Map.of(name, java.util.List.of(value)),
                (k, v) -> true);
    }

    private static Long blockExpiryFor(OpenRouterPool pool, String modelId) {
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        return expiry.get(modelId);
    }

    @Test
    void checkBackendExclusion_blocksWhenResponseProviderIsExcluded() throws Exception {
        OpenRouterPool pool = buildPool("Venice");
        JsonNode body = mapper.readTree("{\"provider\":\"Venice\",\"choices\":[]}");

        pool.checkBackendExclusion("some-model:free", body);

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        assertThat(expiry.get("some-model:free")).isNotNull();
    }

    @Test
    void checkBackendExclusion_doesNothingForReliableBackend() throws Exception {
        OpenRouterPool pool = buildPool("Venice");
        JsonNode body = mapper.readTree("{\"provider\":\"OpenInference\",\"choices\":[]}");

        pool.checkBackendExclusion("openai/gpt-oss-120b:free", body);

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        assertThat(expiry.get("openai/gpt-oss-120b:free")).isNull();
    }

    @Test
    void checkBackendExclusion_noopWhenNoExclusionsConfigured() throws Exception {
        OpenRouterPool pool = buildPool("");
        JsonNode body = mapper.readTree("{\"provider\":\"Venice\",\"choices\":[]}");

        pool.checkBackendExclusion("some-model:free", body);

        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Long> expiry =
                (java.util.concurrent.ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(
                        pool, "rateLimitExpiry");
        assertThat(expiry.get("some-model:free")).isNull();
    }

    @Test
    void poolExposesCorrectProviderKeyAndCapabilities() {
        OpenRouterPool pool = buildPool("Venice");
        assertThat(pool.providerKey()).isEqualTo("openrouter");
        assertThat(pool.supportsTools()).isTrue();
        assertThat(pool.selectedModel()).isEqualTo("openai/gpt-oss-120b:free");
        assertThat(pool.selectedModels()).contains(
                "openai/gpt-oss-120b:free",
                "nvidia/nemotron-3-super-120b-a12b:free");
        assertThat(pool.allBlocked()).isFalse();
    }

    private OpenRouterPool buildPool(String excludedBackends) {
        return new OpenRouterPool(
                "sk-or-test-key",
                "https://openrouter.ai/api/v1",
                "https://example.com",
                "TestApp",
                2000, 1500, 2,
                "openai/gpt-oss-120b:free,nvidia/nemotron-3-super-120b-a12b:free",
                excludedBackends,
                /* selectionTestsEnabled */ false,
                mapper);
    }
}
