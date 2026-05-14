package com.emergentterrain.service;

import com.emergentterrain.llm.TokenAccountant;
import com.emergentterrain.llm.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Cerebras Cloud-backed LlmPool. Phase 7 E1.4.
 *
 * <p>Cerebras's WSE-3 hardware delivers single-digit-millisecond inference per request
 * on free-tier models. The 2026-05-13 test measured 14 ms total round-trip for a
 * tool-call against {@code qwen-3-235b-a22b-instruct-2507} — 10× faster than any other
 * tested provider. This pool's intended role is the {@code runQualityAudit} stage of
 * the narration pipeline, which currently runs on Groq and burns ~100-500 ms per audit.
 * Routing audits to Cerebras would cut Q2+ paper turnaround time materially.
 *
 * <p>Cerebras quirks:
 * <ul>
 *   <li>Free tier is rate-limited by requests-per-minute (~30 RPM per model), not
 *       tokens-per-day. 429 on the free tier carries a {@code "code":"request_quota_exceeded"}
 *       error code.</li>
 *   <li>The {@code time_info} field on every response gives queue / prompt / completion
 *       time in seconds — useful telemetry, not enforced here.</li>
 *   <li>Tool-calling is supported but with a slightly looser arguments schema (no
 *       strict-mode option). Standard OpenAI shape works fine.</li>
 * </ul>
 *
 * <p>Active when {@code app.ai.cerebras.enabled=true}. Disabled by default so a key
 * misconfiguration cannot block startup.
 */
@Component
@ConditionalOnProperty(name = "app.ai.cerebras.enabled", havingValue = "true")
public class CerebrasPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(CerebrasPool.class);

    private static final int  MAX_TOOL_ROUNDS = 5;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final int  MAX_RETRIES = 3;

    private static final String PROVIDER_ID = "cerebras";

    private final String apiKey;
    private final String baseUrl;
    private final int    maxTokens;
    private final int    toolMaxTokens;
    private final String role;
    private final List<String> activeModels;
    private final ConcurrentHashMap<String, Long> rateLimitExpiry = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired(required = false)
    private TokenAccountant tokenAccountant;

    public CerebrasPool(
            @Value("${app.ai.cerebras.apiKey:}") String apiKey,
            @Value("${app.ai.cerebras.baseUrl:https://api.cerebras.ai/v1}") String baseUrl,
            @Value("${app.ai.cerebras.maxTokens:2000}") int maxTokens,
            @Value("${app.ai.cerebras.toolMaxTokens:1500}") int toolMaxTokens,
            @Value("${app.ai.cerebras.parallelism:2}") int parallelism,
            @Value("${app.ai.cerebras.preferredModels:}") String preferredModels,
            @Value("${app.ai.cerebras.role:fast-audit}") String role,
            @Value("${app.ai.selection-tests.enabled:true}") boolean selectionTestsEnabled,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "CerebrasPool: app.ai.cerebras.apiKey is required when "
                            + "app.ai.cerebras.enabled=true");
        }
        if (preferredModels == null || preferredModels.isBlank()) {
            throw new IllegalStateException(
                    "CerebrasPool: app.ai.cerebras.preferredModels is required");
        }
        this.apiKey         = apiKey;
        this.baseUrl        = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxTokens      = maxTokens;
        this.toolMaxTokens  = toolMaxTokens;
        this.role           = role == null ? "" : role.trim();
        this.mapper         = mapper;
        this.slots          = new Semaphore(parallelism);
        this.http           = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        List<String> candidates = Arrays.stream(preferredModels.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        // Phase 9 U5b — universal selection tests. Disabled in unit tests via
        // constructor flag.
        this.activeModels = selectionTestsEnabled
                ? ModelSelectionTester.filterPassing(
                        PROVIDER_ID, candidates,
                        (mid, prompt) -> syncCallWithBackoff(mid, prompt, maxTokens),
                        mid -> (sys, user, toolsJson, exec) ->
                                runToolLoop(mid, sys, user, toolsJson, exec))
                : candidates;

        log.info("CerebrasPool: models={} parallelism={} role={} selection-tests={}",
                activeModels, parallelism, this.role.isEmpty() ? "(none)" : this.role,
                selectionTestsEnabled ? "on" : "off");
    }

    // --- LlmPool ---

    @Override
    public String callSync(String prompt) {
        acquireSlot();
        try {
            return callSyncWithFallback(prompt);
        } finally { slots.release(); }
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        acquireSlot();
        try {
            callStreamWithFallback(prompt, tokenConsumer);
        } finally { slots.release(); }
    }

    @Override public String selectedModel()        { return activeModels.get(0); }
    @Override public List<String> selectedModels() { return activeModels; }
    @Override public int backendCount()            { return 1; }
    @Override public int healthyCount()            { return 1; }

    @Override public String providerKey()          { return PROVIDER_ID; }
    @Override public boolean supportsTools()       { return true; }
    @Override public String role()                 { return role; }

    // Phase 9 U1 — per-model entry points for the unified registry.

    @Override public List<String> modelIds() { return activeModels; }

    @Override public String callSyncOnModel(String modelId, String prompt) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("CerebrasPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { return syncCallWithBackoff(modelId, prompt, maxTokens); }
        finally { slots.release(); }
    }

    @Override public void callStreamOnModel(String modelId, String prompt,
                                              Consumer<String> tokenConsumer) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("CerebrasPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { streamCall(modelId, prompt, tokenConsumer); }
        finally { slots.release(); }
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("CerebrasPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor); }
        finally { slots.release(); }
    }

    @Override
    public boolean allBlocked() {
        // Phase 9 follow-on — when selection tests dropped every candidate (or
        // the pool was never given a model), report blocked so downstream
        // consumers fall back rather than call into an empty pool.
        if (activeModels.isEmpty()) return true;
        long now = System.currentTimeMillis();
        return activeModels.stream().allMatch(m -> {
            Long exp = rateLimitExpiry.get(m);
            return exp != null && now < exp;
        });
    }

    @Override
    public java.time.Instant earliestRecovery() {
        long now = System.currentTimeMillis();
        return activeModels.stream()
                .map(rateLimitExpiry::get)
                .filter(exp -> exp != null && exp > now)
                .min(Long::compareTo)
                .map(java.time.Instant::ofEpochMilli)
                .orElse(java.time.Instant.now());
    }

    @Override
    public com.emergentterrain.llm.ToolCallResponse callWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        acquireSlot();
        try {
            int size  = activeModels.size();
            int start = rrIndex.getAndIncrement() % size;
            RuntimeException last = null;
            for (int i = 0; i < size; i++) {
                String modelId = activeModels.get((start + i) % size);
                if (isModelBlocked(modelId)) {
                    last = new RuntimeException("rate-limit not yet recovered for model=" + modelId);
                    continue;
                }
                try {
                    return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor);
                } catch (RuntimeException e) {
                    last = e;
                    if (i < size - 1) log.warn(
                            "CerebrasPool tools: model={} failed, trying next: {}",
                            modelId, e.getMessage());
                }
            }
            throw last;
        } finally { slots.release(); }
    }

    private com.emergentterrain.llm.ToolCallResponse runToolLoop(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        // Phase 7 F5.2 — capture every tool call into the returned trace.
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = new java.util.ArrayList<>();
        try {
            ArrayNode messages = mapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isBlank())
                messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            JsonNode tools = mapper.readTree(toolsJson);

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                boolean lastRound = round == MAX_TOOL_ROUNDS;
                String payload = buildConversationPayload(
                        modelId, messages, lastRound ? null : tools, toolMaxTokens);
                HttpResponse<String> resp = postChat(payload);
                if (resp.statusCode() != 200) {
                    handleErrorResponse(modelId, resp.statusCode(), resp.headers(), resp.body());
                    throw new RuntimeException("Cerebras API error " + resp.statusCode()
                            + ": " + resp.body());
                }
                JsonNode body    = mapper.readTree(resp.body());
                JsonNode choice  = body.path("choices").get(0);
                JsonNode message = choice.path("message");
                String   reason  = choice.path("finish_reason").asText("stop");
                recordUsage(modelId, body.path("usage"));

                if ("tool_calls".equals(reason) && !lastRound) {
                    messages.add(message);
                    for (JsonNode tc : message.path("tool_calls")) {
                        String callId = tc.path("id").asText();
                        String fnName = tc.path("function").path("name").asText();
                        String fnArgs = tc.path("function").path("arguments").asText("{}");
                        String result;
                        try {
                            result = executor.apply(mapper.writeValueAsString(
                                    mapper.createObjectNode()
                                            .put("name", fnName)
                                            .set("arguments", mapper.readTree(fnArgs))));
                        } catch (Exception ex) {
                            result = "Tool error: " + ex.getMessage();
                        }
                        log.info("CerebrasPool tools: fn={} result_len={}", fnName, result.length());
                        trace.add(new com.emergentterrain.llm.ToolInvocation(
                                fnName, fnArgs, result, round));
                        messages.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", callId)
                                .put("content", result);
                    }
                } else {
                    String content = message.path("content").asText("");
                    if (!trace.isEmpty()) {
                        log.info("CerebrasPool tools: loop closed model={} calls={} rounds=[{}..{}]",
                                modelId, trace.size(),
                                trace.get(0).round(), trace.get(trace.size() - 1).round());
                    }
                    return new com.emergentterrain.llm.ToolCallResponse(
                            content, java.util.List.copyOf(trace));
                }
            }
            return new com.emergentterrain.llm.ToolCallResponse("", java.util.List.copyOf(trace));
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("CerebrasPool tool loop failed", e); }
    }

    // --- Sync + stream ---

    private String callSyncWithFallback(String prompt) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            if (isModelBlocked(modelId)) {
                last = new RuntimeException("rate-limit not yet recovered for model=" + modelId);
                continue;
            }
            try {
                return syncCallWithBackoff(modelId, prompt, maxTokens);
            } catch (RuntimeException e) {
                last = e;
                if (i < size - 1) log.warn(
                        "CerebrasPool: model={} failed, falling through: {}",
                        modelId, e.getMessage());
            }
        }
        throw last;
    }

    private void callStreamWithFallback(String prompt, Consumer<String> tokenConsumer) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            if (isModelBlocked(modelId)) {
                last = new RuntimeException("rate-limit not yet recovered for model=" + modelId);
                continue;
            }
            try {
                streamCall(modelId, prompt, tokenConsumer);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (i < size - 1) log.warn(
                        "CerebrasPool: model={} stream failed, falling through: {}",
                        modelId, e.getMessage());
            }
        }
        throw last;
    }

    private String syncCallWithBackoff(String modelId, String prompt, int tokenLimit) {
        long backoffMs = INITIAL_BACKOFF_MS;
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return syncCall(modelId, prompt, tokenLimit);
            } catch (RuntimeException e) {
                last = e;
                String msg = e.getMessage();
                if (msg != null && msg.contains("429") && attempt < MAX_RETRIES) {
                    // Phase 9 follow-on — honor the just-set rateLimitExpiry from
                    // handleErrorResponse (header- or body-derived) so the retry
                    // actually waits long enough. Falls back to exponential when
                    // no expiry was recorded. Capped at 90s so a misconfigured
                    // long block doesn't stall startup.
                    long waitMs = retryWaitMsFor(modelId, backoffMs);
                    log.warn("CerebrasPool: 429 model={} attempt={} retrying in {}ms",
                            modelId, attempt + 1, waitMs);
                    sleep(waitMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                } else { throw e; }
            }
        }
        throw last;
    }

    private long retryWaitMsFor(String modelId, long fallbackMs) {
        Long expiry = rateLimitExpiry.get(modelId);
        if (expiry == null) return fallbackMs;
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) return fallbackMs;
        return Math.min(remaining + 500, 90_000);
    }

    private String syncCall(String modelId, String prompt, int tokenLimit) {
        try {
            String payload = buildPayload(modelId, prompt, false, tokenLimit);
            HttpResponse<String> resp = postChat(payload);
            if (resp.statusCode() != 200) {
                handleErrorResponse(modelId, resp.statusCode(), resp.headers(), resp.body());
                throw new RuntimeException("Cerebras API error " + resp.statusCode()
                        + ": " + resp.body());
            }
            JsonNode root    = mapper.readTree(resp.body());
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            recordUsage(modelId, root.path("usage"));
            return content;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("CerebrasPool sync call failed", e); }
    }

    private void streamCall(String modelId, String prompt, Consumer<String> tokenConsumer) {
        try {
            String payload = buildPayload(modelId, prompt, true, maxTokens);
            HttpRequest req = chatRequestBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            http.send(req, HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                    .forEach(line -> {
                        try {
                            JsonNode chunk = mapper.readTree(line.substring(6));
                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                recordUsage(modelId, usage);
                            }
                            JsonNode choices = chunk.path("choices");
                            if (!choices.isArray() || choices.isEmpty()) return;
                            JsonNode content = choices.get(0).path("delta").path("content");
                            if (!content.isMissingNode() && !content.isNull()) {
                                String token = content.asText();
                                if (!token.isEmpty()) tokenConsumer.accept(token);
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("CerebrasPool stream call failed", e); }
    }

    // --- HTTP helpers ---

    private HttpResponse<String> postChat(String payload) throws Exception {
        HttpRequest req = chatRequestBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Phase 9 follow-on — per-request read timeout. A response that never
     *  arrives (provider-side hang) surfaces as HttpTimeoutException after
     *  this window, which the retry/fallthrough logic translates into a
     *  fallthrough to the next model / registry handle. Without this, the
     *  HTTP client would wait indefinitely on a stalled response (the
     *  client-level connectTimeout only covers connection setup). */
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(180);

    private HttpRequest.Builder chatRequestBuilder() {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);
    }

    /**
     * Cerebras 429 carries {@code "code":"request_quota_exceeded"} for free-tier
     * per-minute caps. The pool now consults standard rate-limit headers via
     * {@link RateLimitParser} — if Cerebras's gateway sends {@code Retry-After}
     * or {@code x-ratelimit-reset(-after)} (some configurations do, the previous
     * code never read them), we honour the actual recovery window instead of a
     * flat 60s. Falls back to {@link #waitUntilNextMinuteBoundaryMs()} when no
     * header is present — the per-minute cap resets at the top of each minute,
     * so the average true recovery is &lt;60s.
     */
    void handleErrorResponse(String modelId, int statusCode,
                              java.net.http.HttpHeaders headers, String body) {
        if (statusCode == 429) {
            long blockMs = RateLimitParser.inferRetryAfterMs(headers)
                    .orElseGet(this::waitUntilNextMinuteBoundaryMs);
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + blockMs);
            log.warn("CerebrasPool: model={} 429 blocked for {}ms (source={}, free-tier RPM cap)",
                    modelId, blockMs,
                    RateLimitParser.inferRetryAfterMs(headers).isPresent() ? "header" : "next-minute");
        }
    }

    /**
     * Cerebras's free-tier RPM cap resets at the top of each minute. When no
     * {@code Retry-After} header is sent, blocking until the next minute
     * boundary is more accurate than a flat 60s — average recovery is ~30s.
     */
    private long waitUntilNextMinuteBoundaryMs() {
        long nowMs = System.currentTimeMillis();
        long msIntoMinute = nowMs % 60_000;
        long untilTopOfNextMinute = 60_000 - msIntoMinute;
        // Guard against the edge case where msIntoMinute is exactly 0 (we'd
        // return a 60s block instead of 0ms).
        return untilTopOfNextMinute == 60_000 ? 60_000 : untilTopOfNextMinute + 500;
    }

    private void recordUsage(String model, JsonNode usage) {
        if (tokenAccountant == null || usage == null || usage.isMissingNode() || usage.isNull()) return;
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt + completion <= 0) return;
        tokenAccountant.record(new TokenUsage(
                PROVIDER_ID, model, prompt, completion, java.time.Instant.now()));
    }

    // --- Payloads ---

    private String buildPayload(String modelId, String prompt, boolean stream, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", stream);
            node.put("max_tokens", tokenLimit);
            node.put("temperature", 0.7);
            if (stream) node.putObject("stream_options").put("include_usage", true);
            ArrayNode messages = node.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            return mapper.writeValueAsString(node);
        } catch (Exception e) { throw new RuntimeException("CerebrasPool: payload build failed", e); }
    }

    private String buildConversationPayload(String modelId, JsonNode messages,
                                             JsonNode tools, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", false);
            node.put("max_tokens", tokenLimit);
            node.put("temperature", 0.7);
            node.set("messages", messages);
            if (tools != null && tools.isArray() && !tools.isEmpty()) {
                node.set("tools", tools);
                node.put("tool_choice", "auto");
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build conversation payload", e);
        }
    }

    @Override
    public boolean isModelBlocked(String modelId) {
        Long exp = rateLimitExpiry.get(modelId);
        return exp != null && System.currentTimeMillis() < exp;
    }

    private void acquireSlot() {
        try { slots.acquire(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for CerebrasPool slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
