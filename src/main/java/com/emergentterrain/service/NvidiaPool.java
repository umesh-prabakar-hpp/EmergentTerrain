package com.emergentterrain.service;

import com.emergentterrain.llm.LlmCallMetrics;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NVIDIA NIM-backed LlmPool. Phase 7 E1.3.
 *
 * <p>Uses the OpenAI-compatible endpoint at {@code https://integrate.api.nvidia.com/v1}.
 * Request and response shape are nearly identical to Groq and OpenRouter — same
 * {@code chat/completions} path, same {@code tools[]} schema, same SSE streaming format.
 *
 * <p>NVIDIA-specific behaviour the implementation handles:
 * <ul>
 *   <li><b>404 "Function not found for account"</b> — NVIDIA's catalog lists ~129 models
 *       but only a subset are accessible on the free trial tier. Models like
 *       {@code nvidia/nemotron-4-340b-instruct} and {@code nvidia/llama-3.1-nemotron-70b-instruct}
 *       return 404 (not 403) when invoked from a free-tier key. The pool catches these
 *       and permanently disables the offending model so the round-robin doesn't keep
 *       retrying. Verified 2026-05-13 test results.</li>
 *   <li><b>Account verification required at first use</b> — keys generated before the
 *       account's email-verification step return 403 "Authorization failed" on every
 *       inference attempt while {@code /v1/models} still returns 200. This is an
 *       operator-side concern; the pool surfaces it through normal 403 error logging.</li>
 * </ul>
 *
 * <p>Active when {@code app.ai.nvidia.enabled=true}. Disabled by default so a key
 * misconfiguration cannot block startup.
 *
 * <p>Phase 20.11 §migration step 3 (NVIDIA) — gated on
 * {@code app.ai.nvidia.legacy-pool} (default true) so an operator can
 * flip NVIDIA over to a YAML-configured {@link GenericOpenAICompatPool}.
 * Feature-parity status:
 * <ul>
 *   <li>Standard 429 block-window — generic pool handles.</li>
 *   <li>Paid-tier 404 detection ({@code "Not found for account"} →
 *       permanent disable) — NOT YET in generic pool. Without this an
 *       operator who lists a paid-only model will burn free-tier
 *       retries indefinitely.</li>
 *   <li>Monthly-credits body classification (24h block vs the default
 *       60s) — NOT YET in generic pool. Without this a credit-exhausted
 *       model gets retried every minute for the rest of the billing
 *       cycle (~525k wasted calls/month per the existing comment).</li>
 * </ul>
 * Hold the flip on NVIDIA for the parity follow-up.
 */
@Component
@ConditionalOnExpression(
        "${app.ai.nvidia.enabled:false} and ${app.ai.nvidia.legacy-pool:true}")
public class NvidiaPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(NvidiaPool.class);

    private static final int  MAX_TOOL_ROUNDS = 5;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final int  MAX_RETRIES = 3;

    private static final String PROVIDER_ID = "nvidia";

    private final String apiKey;
    private final String baseUrl;
    private final int    maxTokens;
    private final int    toolMaxTokens;
    private final java.util.concurrent.CopyOnWriteArrayList<String> activeModels;
    /**
     * Phase 9 follow-on (2026-05-14) — primary candidates that failed initial
     * selection (e.g. NVIDIA's intermittent 503 ResourceExhausted). Re-tested
     * by {@code ModelRetestService}; on a passing re-probe they move into
     * {@link #activeModels}. Treated as blocked by {@link #isModelBlocked} so
     * the registry doesn't route to them between probes.
     */
    private final Set<String> droppedSelectionTestModels = ConcurrentHashMap.newKeySet();
    // Models that returned 404 "Function not found for account" — permanently disabled
    // for this run; the operator can fix by upgrading their NVIDIA tier or removing
    // the model from app.ai.nvidia.preferredModels and restarting.
    private final Set<String> paidTierModels = ConcurrentHashMap.newKeySet();
    // Models that hit a transient rate-limit — same shape as OpenRouter.
    private final ConcurrentHashMap<String, Long> rateLimitExpiry = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** Phase 9 follow-on — per-request read timeout, driven by
     *  {@code app.ai.http.requestTimeoutSeconds}. See {@link #chatRequestBuilder()}. */
    private final java.time.Duration requestTimeout;

    @Autowired(required = false)
    private TokenAccountant tokenAccountant;

    @Autowired(required = false)
    private LlmCallMetrics callMetrics;

    @Autowired(required = false)
    private com.emergentterrain.llm.LlmTraceWriter traceWriter;

    /** Phase 20.3 — cached tool-schema policy; null-safe (off ⇒ today's behavior). */
    @Autowired(required = false)
    private ToolSchemaCachePolicy toolSchemaCachePolicy;

    /** Phase 20.1 — condense older tool-result messages. Null-safe. */
    @Autowired(required = false)
    private ToolResultCompressor toolResultCompressor;
    /** Phase 20.2 — tag-level history compaction. Null-safe. */
    @Autowired(required = false)
    private ConversationCompactor conversationCompactor;

    public NvidiaPool(
            @Value("${app.ai.nvidia.apiKey:}") String apiKey,
            @Value("${app.ai.nvidia.baseUrl:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${app.ai.nvidia.maxTokens:2000}") int maxTokens,
            @Value("${app.ai.nvidia.toolMaxTokens:1500}") int toolMaxTokens,
            @Value("${app.ai.nvidia.parallelism:3}") int parallelism,
            @Value("${app.ai.nvidia.preferredModels:}") String preferredModels,
            @Value("${app.ai.selection-tests.enabled:true}") boolean selectionTestsEnabled,
            @Value("${app.ai.http.requestTimeoutSeconds:420}") int requestTimeoutSeconds,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "NvidiaPool: app.ai.nvidia.apiKey is required when "
                            + "app.ai.nvidia.enabled=true");
        }
        if (preferredModels == null || preferredModels.isBlank()) {
            throw new IllegalStateException(
                    "NvidiaPool: app.ai.nvidia.preferredModels is required "
                            + "(no model-discovery support yet — NVIDIA's catalog "
                            + "doesn't distinguish free-tier models)");
        }
        this.apiKey         = apiKey;
        this.baseUrl        = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxTokens      = maxTokens;
        this.toolMaxTokens  = toolMaxTokens;
        this.mapper         = mapper;
        this.slots          = new Semaphore(parallelism);
        this.http           = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);

        List<String> candidates = Arrays.stream(preferredModels.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableList());

        // Phase 9 U5b — run the universal selection tests against each candidate
        // model. Models that fail chat + JSON + tool-call probes drop before
        // joining activeModels. Disabled in unit tests via the constructor flag
        // so test pools don't try to hit the real NVIDIA API.
        List<String> passed = selectionTestsEnabled
                ? ModelSelectionTester.filterPassing(
                        PROVIDER_ID, candidates,
                        (mid, prompt) -> syncCallWithBackoff(mid, prompt, maxTokens),
                        mid -> (sys, user, toolsJson, exec) ->
                                runToolLoop(mid, sys, user, toolsJson, exec))
                : candidates;
        this.activeModels = new java.util.concurrent.CopyOnWriteArrayList<>(passed);
        // Phase 9 follow-on — stash failed candidates for periodic re-test.
        if (selectionTestsEnabled) {
            for (String c : candidates) {
                if (!passed.contains(c)) droppedSelectionTestModels.add(c);
            }
        }

        log.info("NvidiaPool: models={} (dropped-awaiting-retest={}) parallelism={} selection-tests={}",
                activeModels, droppedSelectionTestModels, parallelism,
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

    @Override public List<String> modelIds() {
        // Phase 9 follow-on — include dropped models so the registry pre-creates
        // handles for them. They report as blocked until re-test recovery.
        if (droppedSelectionTestModels.isEmpty()) return activeModels;
        List<String> out = new ArrayList<>(activeModels.size() + droppedSelectionTestModels.size());
        out.addAll(activeModels);
        out.addAll(droppedSelectionTestModels);
        return List.copyOf(out);
    }

    @Override public boolean isModelBlocked(String modelId) {
        if (droppedSelectionTestModels.contains(modelId)) return true;
        return isModelUnavailable(modelId);
    }

    @Override
    public List<RetestableModel> pendingRetests() {
        if (droppedSelectionTestModels.isEmpty()) return List.of();
        List<RetestableModel> out = new ArrayList<>();
        for (String mid : droppedSelectionTestModels) {
            out.add(new RetestableModel(
                    mid,
                    (m, prompt) -> syncCallWithBackoff(m, prompt, maxTokens),
                    (sys, user, toolsJson, exec) ->
                            runToolLoop(mid, sys, user, toolsJson, exec)));
        }
        return out;
    }

    @Override
    public void onModelRecovered(String modelId) {
        if (droppedSelectionTestModels.contains(modelId)) {
            if (!activeModels.contains(modelId)) activeModels.add(modelId);
            droppedSelectionTestModels.remove(modelId);
        }
    }

    @Override public String callSyncOnModel(String modelId, String prompt) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("NvidiaPool: unknown model=" + modelId);
        }
        acquireSlot();
        try {
            return syncCallWithBackoff(modelId, prompt, maxTokens);
        } finally { slots.release(); }
    }

    @Override public void callStreamOnModel(String modelId, String prompt,
                                              Consumer<String> tokenConsumer) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("NvidiaPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { streamCall(modelId, prompt, tokenConsumer); }
        finally { slots.release(); }
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException("NvidiaPool: unknown model=" + modelId);
        }
        acquireSlot();
        try { return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor); }
        finally { slots.release(); }
    }

    @Override
    public boolean allBlocked() {
        // Phase 9 follow-on — empty activeModels means selection dropped every
        // candidate (or no model was configured). Report blocked so consumers
        // fall back instead of calling into an empty pool.
        if (activeModels.isEmpty()) return true;
        long now = System.currentTimeMillis();
        return activeModels.stream().allMatch(m -> {
            if (paidTierModels.contains(m)) return true;  // permanently unavailable
            Long exp = rateLimitExpiry.get(m);
            return exp != null && now < exp;
        });
    }

    @Override
    public java.time.Instant earliestRecovery() {
        long now = System.currentTimeMillis();
        return activeModels.stream()
                .filter(m -> !paidTierModels.contains(m))
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
                if (isModelUnavailable(modelId)) {
                    last = new RuntimeException("model unavailable: " + modelId);
                    continue;
                }
                try {
                    return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor);
                } catch (RuntimeException e) {
                    last = e;
                    if (i < size - 1) log.warn(
                            "NvidiaPool tools: model={} failed, trying next: {}",
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
                // Phase 20.3 — cached tool-schema policy; see GroqPool comment.
                boolean sendTools = toolSchemaCachePolicy == null
                        ? !lastRound
                        : toolSchemaCachePolicy.shouldSendTools(round, lastRound);
                // Phase 20.1 — condense older tool-result messages.
                if (toolResultCompressor != null) {
                    toolResultCompressor.condenseOlderToolMessages(messages);
                }
                // Phase 20.2 — tag-level history compaction.
                if (conversationCompactor != null) {
                    conversationCompactor.compact(messages);
                }
                String payload = buildConversationPayload(
                        modelId, messages, sendTools ? tools : null, toolMaxTokens);
                String roundOutcome = "ok";
                HttpResponse<String> resp;
                long t0 = System.currentTimeMillis();
                int respStatus = 0;
                String respBody = "";
                try {
                    resp = postChat(payload);
                    respStatus = resp.statusCode();
                    respBody = resp.body();
                    if (resp.statusCode() != 200) {
                        roundOutcome = "http_error";
                        handleErrorResponse(modelId, resp.statusCode(), resp.headers(), resp.body());
                        throw new RuntimeException("NVIDIA API error " + resp.statusCode()
                                + ": " + resp.body());
                    }
                } catch (RuntimeException e) {
                    if ("ok".equals(roundOutcome)) roundOutcome = "io_error";
                    recordCall(modelId, "tools", roundOutcome);
                    if (traceWriter != null) {
                        traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                                PROVIDER_ID, modelId, "tools", respStatus,
                                System.currentTimeMillis() - t0, payload, respBody);
                    }
                    throw e;
                } catch (Exception e) {
                    recordCall(modelId, "tools", "io_error");
                    if (traceWriter != null) {
                        traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                                PROVIDER_ID, modelId, "tools", respStatus,
                                System.currentTimeMillis() - t0, payload, respBody);
                    }
                    throw e;
                }
                recordCall(modelId, "tools", roundOutcome);
                if (traceWriter != null) {
                    traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                            PROVIDER_ID, modelId, "tools", respStatus,
                            System.currentTimeMillis() - t0, payload, respBody);
                }
                JsonNode body    = mapper.readTree(resp.body());
                JsonNode choice  = body.path("choices").get(0);
                JsonNode message = choice.path("message");
                String   reason  = choice.path("finish_reason").asText("stop");
                recordUsage(modelId, body.path("usage"));

                if ("tool_calls".equals(reason) && !lastRound) {
                    messages.add(message);
                    for (JsonNode tc : message.path("tool_calls")) {
                        String providerCallId = tc.path("id").asText();
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
                        // Phase 11 C1 — stable harness-assigned ID. See GroqPool.
                        String harnessCallId = "call_" + (trace.size() + 1);
                        log.info("NvidiaPool tools: fn={} call_id={} result_len={}",
                                fnName, harnessCallId, result.length());
                        trace.add(new com.emergentterrain.llm.ToolInvocation(
                                fnName, fnArgs, result, round, harnessCallId));
                        messages.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", providerCallId)
                                .put("content", "[" + harnessCallId + "] " + result);
                    }
                } else {
                    String content = message.path("content").asText("");
                    if (content.isBlank()) content = message.path("reasoning_content").asText("");
                    if (!trace.isEmpty()) {
                        log.info("NvidiaPool tools: loop closed model={} calls={} rounds=[{}..{}]",
                                modelId, trace.size(),
                                trace.get(0).round(), trace.get(trace.size() - 1).round());
                    }
                    return new com.emergentterrain.llm.ToolCallResponse(
                            content, java.util.List.copyOf(trace));
                }
            }
            return new com.emergentterrain.llm.ToolCallResponse("", java.util.List.copyOf(trace));
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("NvidiaPool tool loop failed", e); }
    }

    // --- Sync + stream ---

    private String callSyncWithFallback(String prompt) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            if (isModelUnavailable(modelId)) {
                last = new RuntimeException("model unavailable: " + modelId);
                continue;
            }
            try {
                return syncCallWithBackoff(modelId, prompt, maxTokens);
            } catch (RuntimeException e) {
                last = e;
                if (i < size - 1) log.warn(
                        "NvidiaPool: model={} failed, falling through: {}",
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
            if (isModelUnavailable(modelId)) {
                last = new RuntimeException("model unavailable: " + modelId);
                continue;
            }
            try {
                streamCall(modelId, prompt, tokenConsumer);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (i < size - 1) log.warn(
                        "NvidiaPool: model={} stream failed, falling through: {}",
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
                    // Phase 9 follow-on — honor rateLimitExpiry set by
                    // handleErrorResponse so retries wait the provider's actual
                    // retry-after rather than a fixed exponential.
                    long waitMs = retryWaitMsFor(modelId, backoffMs);
                    log.warn("NvidiaPool: 429 model={} attempt={} retrying in {}ms",
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
        String outcome = "ok";
        String payload = buildPayload(modelId, prompt, false, tokenLimit);
        long t0 = System.currentTimeMillis();
        int respStatus = 0;
        String respBody = "";
        try {
            HttpResponse<String> resp = postChat(payload);
            respStatus = resp.statusCode();
            respBody = resp.body();
            if (resp.statusCode() != 200) {
                outcome = "http_error";
                handleErrorResponse(modelId, resp.statusCode(), resp.headers(), resp.body());
                throw new RuntimeException("NVIDIA API error " + resp.statusCode()
                        + ": " + resp.body());
            }
            JsonNode root    = mapper.readTree(resp.body());
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) content = message.path("reasoning_content").asText("");
            recordUsage(modelId, root.path("usage"));
            return content;
        } catch (RuntimeException e) {
            if ("ok".equals(outcome)) outcome = "io_error";
            throw e;
        } catch (Exception e) {
            outcome = "io_error";
            throw new RuntimeException("NvidiaPool sync call failed", e);
        } finally {
            recordCall(modelId, "sync", outcome);
            if (traceWriter != null) {
                traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                        PROVIDER_ID, modelId, "sync", respStatus,
                        System.currentTimeMillis() - t0, payload, respBody);
            }
        }
    }

    private void streamCall(String modelId, String prompt, Consumer<String> tokenConsumer) {
        String outcome = "ok";
        String payload = buildPayload(modelId, prompt, true, maxTokens);
        long t0 = System.currentTimeMillis();
        StringBuilder accum = new StringBuilder();
        int respStatus = 0;
        try {
            HttpRequest req = chatRequestBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<java.util.stream.Stream<String>> sresp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            respStatus = sresp.statusCode();
            sresp.body()
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
                                if (!token.isEmpty()) {
                                    accum.append(token);
                                    tokenConsumer.accept(token);
                                }
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (RuntimeException e) {
            outcome = "io_error";
            throw e;
        } catch (Exception e) {
            outcome = "io_error";
            throw new RuntimeException("NvidiaPool stream call failed", e);
        } finally {
            recordCall(modelId, "stream", outcome);
            if (traceWriter != null) {
                traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                        PROVIDER_ID, modelId, "stream", respStatus,
                        System.currentTimeMillis() - t0, payload, accum.toString());
            }
        }
    }

    private void recordCall(String modelId, String mode, String outcome) {
        if (callMetrics != null) callMetrics.record(PROVIDER_ID, modelId, mode, outcome);
    }

    // --- HTTP helpers ---

    private HttpResponse<String> postChat(String payload) throws Exception {
        HttpRequest req = chatRequestBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder chatRequestBuilder() {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(requestTimeout);
    }

    /**
     * NVIDIA's 404 on a chat-completions call with a valid model id has a distinctive
     * body shape: {@code "Function 'xxx-xxx': Not found for account 'YYY'"}. This means
     * the model is in NVIDIA's catalog but not entitled to the caller's free-tier
     * account. We permanently disable such models so the pool's round-robin doesn't
     * keep retrying.
     *
     * <p>For 429 we first consult standard rate-limit headers via
     * {@link RateLimitParser}. NVIDIA's free tier has historically not published
     * {@code Retry-After}, but if a future gateway revision starts emitting it
     * we'll honour the real recovery window. Falls back to body inspection to
     * distinguish per-minute throttles (short block) from monthly-credit
     * exhaustion (24h block — the credits don't return until the next billing
     * cycle, so a flat 60s would re-attack the API ~525k times that month).
     */
    void handleErrorResponse(String modelId, int statusCode,
                              java.net.http.HttpHeaders headers, String body) {
        if (statusCode == 404 && body != null
                && body.contains("Not found for account")) {
            log.warn("NvidiaPool: model={} is paid-tier only — permanently disabled "
                    + "(free-tier account lacks entitlement; check NVIDIA dashboard)",
                    modelId);
            paidTierModels.add(modelId);
            return;
        }
        if (statusCode == 429) {
            long blockMs = RateLimitParser.inferRetryAfterMs(headers)
                    .orElseGet(() -> inferBlockMsFromBody(body));
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + blockMs);
            log.warn("NvidiaPool: model={} 429 blocked for {}ms (source={})",
                    modelId, blockMs,
                    RateLimitParser.inferRetryAfterMs(headers).isPresent() ? "header" : "body-fallback");
        }
    }

    /**
     * NVIDIA-specific body classifier: distinguishes a per-minute throttle (short
     * recovery) from a monthly-credits exhaustion (next-month recovery). Free-tier
     * credit exhaustion shows up with words like {@code credit}, {@code monthly},
     * or {@code quota exceeded}; per-minute throttles return shorter / less
     * specific messages.
     */
    private long inferBlockMsFromBody(String body) {
        if (body == null) return 60_000L;
        String lower = body.toLowerCase();
        if (lower.contains("credit") || lower.contains("monthly")
                || lower.contains("quota exceeded") || lower.contains("usage limit")) {
            return 24L * 60 * 60 * 1000;  // 24h — the operator must wait until renewal
        }
        return 60_000L;
    }

    private void recordUsage(String model, JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt + completion <= 0) return;
        // Phase 20.4 step 2b-2 — per-paper accumulator (see GroqPool).
        com.emergentterrain.llm.LlmCallContext.recordTokenUsage(prompt, completion);
        if (tokenAccountant != null) {
            tokenAccountant.record(new TokenUsage(
                    PROVIDER_ID, model, prompt, completion, java.time.Instant.now()));
        }
    }

    // --- Payloads ---

    private String buildPayload(String modelId, String prompt, boolean stream, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", stream);
            node.put("max_tokens", tokenLimit);
            node.put("temperature",
                    com.emergentterrain.llm.LlmCallContext.currentTemperature().orElse(0.7));
            if (com.emergentterrain.llm.LlmCallContext.isJsonOutputMode()) {
                node.putObject("response_format").put("type", "json_object");
            }
            if (stream) node.putObject("stream_options").put("include_usage", true);
            ArrayNode messages = node.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            return mapper.writeValueAsString(node);
        } catch (Exception e) { throw new RuntimeException("NvidiaPool: payload build failed", e); }
    }

    private String buildConversationPayload(String modelId, JsonNode messages,
                                             JsonNode tools, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", false);
            node.put("max_tokens", tokenLimit);
            node.put("temperature",
                    com.emergentterrain.llm.LlmCallContext.currentTemperature().orElse(0.7));
            if (com.emergentterrain.llm.LlmCallContext.isJsonOutputMode()) {
                node.putObject("response_format").put("type", "json_object");
            }
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

    boolean isModelUnavailable(String modelId) {
        if (paidTierModels.contains(modelId)) return true;
        Long exp = rateLimitExpiry.get(modelId);
        return exp != null && System.currentTimeMillis() < exp;
    }

    private void acquireSlot() {
        try { slots.acquire(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for NvidiaPool slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
