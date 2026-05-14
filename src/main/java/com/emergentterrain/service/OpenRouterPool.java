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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OpenRouter-backed LlmPool. Phase 7 E1.2.
 *
 * <p>Uses the operator-configured {@code preferredModels} list directly — no startup
 * benchmarking dance (OpenRouter's model catalog is too noisy for the GroqPool
 * Borda-ranking approach, and free-tier users hit rate-limits trying to rank).
 *
 * <p>OpenRouter quirks the implementation handles:
 * <ul>
 *   <li>Requires {@code HTTP-Referer} and {@code X-Title} headers for free-tier routing
 *       (else requests are deprioritised; OpenRouter docs §2.3).</li>
 *   <li>Returns a {@code provider} field on every response naming the upstream backend
 *       (e.g. {@code "OpenInference"}, {@code "Venice"}, {@code "NVIDIA"}). The pool
 *       can be configured to exclude specific backends — Venice in particular has a
 *       sub-minute global free-tier cap that makes its hosted models unusable for
 *       sustained traffic (verified 2026-05-13 test results).</li>
 *   <li>Error shape on rate-limit: {@code {"error":{"code":429,"metadata":{
 *       "retry_after_seconds":N,"provider_name":"Venice"}}}}. The retry-after is
 *       respected per-model; persistent Venice-shaped 429s trigger a block.</li>
 *   <li>Free models often have a {@code :free} suffix on their id. Token usage in the
 *       response carries {@code "cost":0} as a sanity check that the model was free.</li>
 * </ul>
 *
 * <p>Active when {@code app.ai.openrouter.enabled=true}. Disabled by default so a key
 * misconfiguration cannot block startup.
 */
@Component
@ConditionalOnProperty(name = "app.ai.openrouter.enabled", havingValue = "true")
public class OpenRouterPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterPool.class);

    private static final int  MAX_TOOL_ROUNDS = 5;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final int  MAX_RETRIES = 3;

    private static final String PROVIDER_ID = "openrouter";

    private final String apiKey;
    private final String baseUrl;
    private final String siteReferer;
    private final String siteTitle;
    private final int    maxTokens;
    private final int    toolMaxTokens;
    private final List<String> activeModels;
    private final Set<String>  excludedBackends;
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final HttpClient http;
    // Per-model rate-limit expiry (epoch-ms): set when a 429 arrives, cleared on success.
    private final ConcurrentHashMap<String, Long> rateLimitExpiry = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private TokenAccountant tokenAccountant;

    public OpenRouterPool(
            @Value("${app.ai.openrouter.apiKey:}") String apiKey,
            @Value("${app.ai.openrouter.baseUrl:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${app.ai.openrouter.siteReferer:}") String siteReferer,
            @Value("${app.ai.openrouter.siteTitle:}") String siteTitle,
            @Value("${app.ai.openrouter.maxTokens:2000}") int maxTokens,
            @Value("${app.ai.openrouter.toolMaxTokens:1500}") int toolMaxTokens,
            @Value("${app.ai.openrouter.parallelism:2}") int parallelism,
            @Value("${app.ai.openrouter.preferredModels:}") String preferredModels,
            @Value("${app.ai.openrouter.excludedBackends:}") String excludedBackends,
            @Value("${app.ai.selection-tests.enabled:true}") boolean selectionTestsEnabled,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenRouterPool: app.ai.openrouter.apiKey is required when "
                            + "app.ai.openrouter.enabled=true");
        }
        if (preferredModels == null || preferredModels.isBlank()) {
            throw new IllegalStateException(
                    "OpenRouterPool: app.ai.openrouter.preferredModels is required "
                            + "(no model-discovery support yet)");
        }
        this.apiKey         = apiKey;
        this.baseUrl        = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.siteReferer    = siteReferer == null ? "" : siteReferer;
        this.siteTitle      = siteTitle == null ? "" : siteTitle;
        this.maxTokens      = maxTokens;
        this.toolMaxTokens  = toolMaxTokens;
        this.mapper         = mapper;
        this.slots          = new Semaphore(parallelism);
        this.http           = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        List<String> candidates = Arrays.stream(preferredModels.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        this.excludedBackends = excludedBackends == null || excludedBackends.isBlank()
                ? Set.of()
                : Arrays.stream(excludedBackends.split(","))
                        .map(String::trim).filter(s -> !s.isBlank())
                        .collect(Collectors.toUnmodifiableSet());

        // Phase 9 U5b — universal selection tests. Disabled in unit tests via
        // constructor flag.
        this.activeModels = selectionTestsEnabled
                ? ModelSelectionTester.filterPassing(
                        PROVIDER_ID, candidates,
                        (mid, prompt) -> syncCallWithBackoff(mid, prompt, maxTokens),
                        mid -> (sys, user, toolsJson, exec) ->
                                runToolLoop(mid, sys, user, toolsJson, exec))
                : candidates;

        log.info("OpenRouterPool: models={} parallelism={} excludedBackends={} selection-tests={}",
                activeModels, parallelism, this.excludedBackends,
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

    // Phase 9 U1 — per-model entry points for the unified registry.

    @Override public List<String> modelIds() { return activeModels; }

    @Override public String callSyncOnModel(String modelId, String prompt) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("OpenRouterPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { return syncCallWithBackoff(modelId, prompt, maxTokens); }
        finally { slots.release(); }
    }

    @Override public void callStreamOnModel(String modelId, String prompt,
                                              Consumer<String> tokenConsumer) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("OpenRouterPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { streamCall(modelId, prompt, tokenConsumer); }
        finally { slots.release(); }
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("OpenRouterPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor); }
        finally { slots.release(); }
    }

    @Override
    public boolean allBlocked() {
        // Phase 9 follow-on — empty activeModels means selection dropped every
        // candidate. Report blocked so consumers fall back.
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
                            "OpenRouterPool tools: model={} failed, trying next: {}",
                            modelId, e.getMessage());
                }
            }
            throw last;
        } finally { slots.release(); }
    }

    // --- Tool loop ---

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
                    throw new RuntimeException("OpenRouter API error " + resp.statusCode()
                            + ": " + resp.body());
                }
                JsonNode body    = mapper.readTree(resp.body());
                checkBackendExclusion(modelId, body);
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
                        log.info("OpenRouterPool tools: fn={} result_len={}", fnName, result.length());
                        trace.add(new com.emergentterrain.llm.ToolInvocation(
                                fnName, fnArgs, result, round));
                        messages.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", callId)
                                .put("content", result);
                    }
                } else {
                    String content = message.path("content").asText("");
                    if (content.isBlank()) content = message.path("reasoning").asText("");
                    if (!trace.isEmpty()) {
                        log.info("OpenRouterPool tools: loop closed model={} calls={} rounds=[{}..{}]",
                                modelId, trace.size(),
                                trace.get(0).round(), trace.get(trace.size() - 1).round());
                    }
                    return new com.emergentterrain.llm.ToolCallResponse(
                            content, java.util.List.copyOf(trace));
                }
            }
            return new com.emergentterrain.llm.ToolCallResponse("", java.util.List.copyOf(trace));
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("OpenRouterPool tool loop failed", e); }
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
                        "OpenRouterPool: model={} failed, falling through: {}",
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
                        "OpenRouterPool: model={} stream failed, falling through: {}",
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
                    // Phase 9 follow-on — prefer rateLimitExpiry (set from
                    // header + body by handleErrorResponse) over the
                    // body-only parseRetryAfterMs. Cap at 90s.
                    long waitMs = retryWaitMsFor(modelId,
                            parseRetryAfterMs(msg).orElse(backoffMs));
                    log.warn("OpenRouterPool: 429 model={} attempt={} retrying in {}ms",
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
                throw new RuntimeException("OpenRouter API error " + resp.statusCode()
                        + ": " + resp.body());
            }
            JsonNode root    = mapper.readTree(resp.body());
            checkBackendExclusion(modelId, root);
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) content = message.path("reasoning").asText("");
            recordUsage(modelId, root.path("usage"));
            return content;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("OpenRouterPool sync call failed", e); }
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
                            // OpenRouter passes usage in the final chunk when
                            // stream_options.include_usage=true is set on the request.
                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                recordUsage(modelId, usage);
                            }
                            // Backend-exclusion check on stream chunks too
                            JsonNode provider = chunk.path("provider");
                            if (!provider.isMissingNode()) {
                                checkBackendExclusion(modelId, chunk);
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
        catch (Exception e) { throw new RuntimeException("OpenRouterPool stream call failed", e); }
    }

    // --- HTTP helpers ---

    private HttpResponse<String> postChat(String payload) throws Exception {
        HttpRequest req = chatRequestBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Phase 9 follow-on — per-request read timeout to surface hung responses. */
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofMinutes(7);

    private HttpRequest.Builder chatRequestBuilder() {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);
        if (!siteReferer.isBlank()) b = b.header("HTTP-Referer", siteReferer);
        if (!siteTitle.isBlank())   b = b.header("X-Title", siteTitle);
        return b;
    }

    /**
     * OpenRouter wraps upstream rate-limits in {@code error.metadata.retry_after_seconds}
     * with a {@code provider_name}. If the provider is in the excluded set we block the
     * model for a generous fixed window so the pool stops routing to it.
     *
     * <p>Header-first ordering: standard {@code Retry-After} / {@code x-ratelimit-reset(-after)}
     * are consulted via {@link RateLimitParser} before the OpenRouter-specific
     * body shape. Whichever yields a longer recovery wins, so an excluded backend
     * doesn't get re-tried earlier than its 5-minute exile.
     */
    void handleErrorResponse(String modelId, int statusCode,
                              java.net.http.HttpHeaders headers, String body) {
        if (statusCode != 429) return;
        long headerRetryMs = RateLimitParser.inferRetryAfterMs(headers).orElse(0L);
        try {
            JsonNode err = mapper.readTree(body).path("error");
            if (err.isMissingNode()) {
                // No body envelope — apply header window or short default.
                long fallback = headerRetryMs > 0 ? headerRetryMs : 30_000;
                rateLimitExpiry.put(modelId, System.currentTimeMillis() + fallback);
                return;
            }
            JsonNode meta = err.path("metadata");
            long retryAfterMs = meta.path("retry_after_seconds").asLong(60) * 1000L;
            // Take the longer of header- and body-derived windows.
            retryAfterMs = Math.max(retryAfterMs, headerRetryMs);
            String providerName = meta.path("provider_name").asText("");
            if (!providerName.isBlank() && excludedBackends.contains(providerName)) {
                // Block excluded-backend models for a longer window — Venice's
                // sub-minute cap is shared across all OpenRouter users globally.
                retryAfterMs = Math.max(retryAfterMs, 5 * 60 * 1000L);
                log.warn("OpenRouterPool: model={} backend={} (excluded) blocked for {}ms",
                        modelId, providerName, retryAfterMs);
            } else {
                log.warn("OpenRouterPool: model={} 429 blocked for {}ms (provider={})",
                        modelId, retryAfterMs, providerName);
            }
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + retryAfterMs);
        } catch (Exception e) {
            // Body parse failed — use header window if any, else a short default
            // so the round-robin can move on to the next model.
            long fallback = headerRetryMs > 0 ? headerRetryMs : 30_000;
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + fallback);
        }
    }

    void checkBackendExclusion(String modelId, JsonNode body) {
        if (excludedBackends.isEmpty()) return;
        String provider = body.path("provider").asText("");
        if (!provider.isBlank() && excludedBackends.contains(provider)) {
            log.warn("OpenRouterPool: model={} routed via excluded backend={} — "
                    + "blocking model for 5 min to force re-route",
                    modelId, provider);
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + 5 * 60 * 1000L);
        }
    }

    private void recordUsage(String model, JsonNode usage) {
        if (tokenAccountant == null || usage == null || usage.isMissingNode() || usage.isNull()) return;
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt + completion <= 0) return;
        tokenAccountant.record(new TokenUsage(
                PROVIDER_ID, model, prompt, completion, java.time.Instant.now()));
    }

    // OpenRouter retry-after is in seconds (integer or float) embedded in the error metadata.
    static java.util.OptionalLong parseRetryAfterMs(String message) {
        Matcher m = Pattern.compile("retry_after_seconds[^0-9]*([0-9]+(?:\\.[0-9]+)?)").matcher(message);
        if (m.find()) {
            try {
                return java.util.OptionalLong.of((long)(Double.parseDouble(m.group(1)) * 1000) + 500);
            } catch (NumberFormatException ignored) {}
        }
        return java.util.OptionalLong.empty();
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
        } catch (Exception e) { throw new RuntimeException("OpenRouterPool: payload build failed", e); }
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

    // --- Plumbing ---

    @Override
    public boolean isModelBlocked(String modelId) {
        Long exp = rateLimitExpiry.get(modelId);
        return exp != null && System.currentTimeMillis() < exp;
    }

    private void acquireSlot() {
        try { slots.acquire(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for OpenRouterPool slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
