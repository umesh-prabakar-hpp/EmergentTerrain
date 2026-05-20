package com.emergentterrain.service;

import com.emergentterrain.llm.LlmCallContext;
import com.emergentterrain.llm.LlmCallMetrics;
import com.emergentterrain.llm.TokenAccountant;
import com.emergentterrain.llm.TokenUsage;
import com.emergentterrain.llm.TpmBudgetGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 20.11 — single pool class that speaks the OpenAI-compat
 * chat-completions protocol, parameterized by {@link ProviderConfig}.
 * One Spring bean per configured provider (registered dynamically by
 * {@code ProviderConfigLoader} — follow-up commit).
 *
 * <h2>Why this exists</h2>
 * Pre-Phase-20.11 the five existing pool classes (Groq, Cerebras,
 * NVIDIA, OpenRouter, Ollama) carried ~80% identical logic — payload
 * shape, response parse, 429 handling, token accounting, tool-call
 * protocol. Adding a sixth provider meant ~600-1000 lines of new Java.
 * 20.11 extracts the shared OpenAI-compat protocol into one class so
 * new providers are a config-only edit.
 *
 * <h2>Scope of this commit (step 1)</h2>
 * <ul>
 *   <li>{@link #callSync} / {@link #callSyncOnModel} — chat completions
 *       sync path with round-robin model selection + cross-model fallthrough
 *       on RuntimeException.</li>
 *   <li>{@link #syncCallWithBackoff} — retry on 429 with retry-after
 *       parsing (header-source by default; body-source for Groq-style
 *       providers configured via {@link ProviderConfig#retryAfterSource}).</li>
 *   <li>Per-model {@link #rateLimitExpiry} consulted by
 *       {@link #isModelBlocked} so the registry's availability filter skips
 *       blocked models. Same Phase 18 contract as the existing pools.</li>
 *   <li>Token accounting via {@link LlmCallContext#recordTokenUsage}
 *       (per-paper accumulator) plus {@link TokenAccountant} when wired.</li>
 * </ul>
 *
 * <h2>Deferred (follow-up commits)</h2>
 * <ul>
 *   <li>Tool-call path ({@code runToolLoop}) — currently throws
 *       {@link UnsupportedOperationException}; {@link #supportsTools}
 *       gates the registry's tool-enabled filter.</li>
 *   <li>Stream path ({@link #callStream}) — likewise throws.</li>
 *   <li>Selection probes — pool currently trusts the static / discovered
 *       model list. {@code ModelSelectionTester} integration TBD.</li>
 *   <li>Model discovery via {@link ProviderConfig#modelsDiscoveryPath}
 *       — currently uses the static {@code models} list only.</li>
 * </ul>
 */
public class GenericOpenAICompatPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(GenericOpenAICompatPool.class);

    private static final int  MAX_RETRIES        = 4;
    private static final long INITIAL_BACKOFF_MS = 10_000;
    private static final long DEFAULT_BLOCK_MS   = 60_000;
    /** Same per-pool ceiling as the legacy pools (Phase 7 F4 raised 2 → 5). */
    private static final int  MAX_TOOL_ROUNDS    = 5;
    /** Per-round output cap for tool calls. The professor LLM emits at most this
     *  many tokens per round (synthesis usually fits in 500-1000; cap of 1500
     *  matches the existing pools and gives room for chain-of-thought reasoning). */
    private static final int  TOOL_MAX_TOKENS    = 1500;

    private final ProviderConfig cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final List<String> activeModels;
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger();
    /** Per-model rate-limit expiry (epoch-millis). Mirrors the Phase-18 pattern
     *  in the existing pools — {@link #isModelBlocked} consults this so the
     *  registry's availability filter skips blocked models. */
    private final ConcurrentHashMap<String, Long> rateLimitExpiry = new ConcurrentHashMap<>();
    private final Duration requestTimeout;

    @Autowired(required = false)
    private TokenAccountant tokenAccountant;
    @Autowired(required = false)
    private LlmCallMetrics callMetrics;
    @Autowired(required = false)
    private TpmBudgetGate budgetGate;
    @Autowired(required = false)
    private com.emergentterrain.llm.BudgetGateMetrics budgetGateMetrics;
    /** Phase 20.3 — cached tool-schema policy. Null-safe (off ⇒ legacy behavior). */
    @Autowired(required = false)
    private ToolSchemaCachePolicy toolSchemaCachePolicy;
    /** Phase 20.1 — condense older tool-result messages. Null-safe. */
    @Autowired(required = false)
    private ToolResultCompressor toolResultCompressor;
    @Autowired(required = false)
    private com.emergentterrain.llm.LlmTraceWriter traceWriter;
    /** Phase 20.2 — tag-level history compaction. Null-safe. */
    @Autowired(required = false)
    private ConversationCompactor conversationCompactor;

    public GenericOpenAICompatPool(ProviderConfig cfg,
                                    ObjectMapper mapper,
                                    int parallelism,
                                    Duration requestTimeout,
                                    boolean selectionTestsEnabled) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
        this.slots = new Semaphore(Math.max(1, parallelism));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // For step 1: trust the static models list. Discovery via
        // modelsDiscoveryPath is a follow-up — pool warns if both are unset.
        List<String> models = cfg.models() == null ? List.of() : cfg.models();
        this.activeModels = new java.util.concurrent.CopyOnWriteArrayList<>(models);
        if (activeModels.isEmpty()) {
            log.warn("GenericOpenAICompatPool({}): activeModels is empty — pool will fail every call. "
                    + "Set app.ai.providers.{}.models or .models-discovery-path.",
                    cfg.providerKey(), cfg.providerKey());
            return;
        }

        // Phase 9 U5 / Phase 20.11 fixup — universal selection tests (chat +
        // JSON, plus tool-call for supportsTools=true pools). Restores parity
        // with the legacy CerebrasPool/OpenRouterPool/NvidiaPool/GroqPool that
        // each ran ModelSelectionTester.filterPassing at construction. The
        // syncDispatch deliberately bypasses callSyncOnModel's activeModels
        // gate (which we seed with candidates above) and calls the low-level
        // syncCallWithBackoff so the probe runs even before the final
        // activeModels list is fixed. supportsTools=false ⇒ chat + JSON only;
        // the tool-call probe is meaningful only for tool-capable pools.
        if (selectionTestsEnabled) {
            List<String> candidates = List.copyOf(activeModels);
            List<String> passed = ModelSelectionTester.filterPassing(
                    cfg.providerKey(),
                    candidates,
                    (mid, prompt) -> syncCallWithBackoff(mid, prompt, cfg.contextWindow()),
                    cfg.supportsTools()
                            ? mid -> (sys, user, toolsJson, exec) ->
                                    runToolLoop(mid, sys, user, toolsJson, exec)
                            : null);
            if (passed.isEmpty()) {
                // Mirror OllamaPool: every model failed — likely the provider
                // is unreachable or the API key is bad. Keep candidates in
                // activeModels so the pool isn't permanently dead; the
                // runtime block-state + token-budget plumbing pick up
                // recovery when the provider comes back. Operator sees the
                // WARN per-model from filterPassing and can investigate.
                log.warn("GenericOpenAICompatPool({}): selection tests failed for "
                                + "all candidates {} — keeping unverified models "
                                + "in activeModels. Investigate: is the API key set? "
                                + "is the provider's baseUrl reachable? are the model "
                                + "ids spelled correctly?",
                        cfg.providerKey(), candidates);
            } else {
                activeModels.clear();
                activeModels.addAll(passed);
            }
        }

        log.info("GenericOpenAICompatPool({}): parallelism={} models={} contextWindow={} supportsTools={} selection-tests={}",
                cfg.providerKey(), parallelism, activeModels, cfg.contextWindow(),
                cfg.supportsTools(), selectionTestsEnabled ? "on" : "off");
    }

    // ── LlmPool surface ────────────────────────────────────────────────────

    @Override public String providerKey() { return cfg.providerKey(); }

    @Override public List<String> modelIds() {
        return Collections.unmodifiableList(activeModels);
    }

    @Override public String selectedModel() {
        return activeModels.isEmpty() ? "" : activeModels.get(0);
    }

    @Override public List<String> selectedModels() { return modelIds(); }

    @Override public int backendCount() { return 1; }
    @Override public int healthyCount() { return 1; }

    /**
     * Phase 20.11 §migration — honor the YAML-declared role so a
     * generic-pool-backed provider participates in LlmRouter's
     * role-indexed routing (e.g. {@code "fast-audit"}) the same way the
     * hand-written pools do. Default empty string = "primary chain only".
     */
    @Override public String role() { return cfg.role() == null ? "" : cfg.role(); }

    /**
     * Phase 20.11 §migration (Groq fast-tier parity) — when
     * {@link ProviderConfig#fastTier()} is set, publish the active
     * model list via {@code fastTierModelIds()} so
     * {@code FastModelRegistry} picks them up for the fast-audit role.
     * Typical operator pairing for a fast-tier pool: {@code fast-tier:
     * true} + {@code supports-tools: false} so
     * {@code PrimaryModelRegistry}'s existing {@code supportsTools}
     * filter excludes the same models from the primary chain — the
     * two registries see disjoint lists, mirroring the legacy
     * {@code GroqPool} which keeps separate {@code activeModels} +
     * {@code fastTierActiveModels} fields internally.
     */
    @Override public List<String> fastTierModelIds() {
        return cfg.fastTier() ? Collections.unmodifiableList(activeModels) : List.of();
    }

    @Override public boolean supportsTools() {
        // Phase 20.11 step 3 — honors the yaml `supports-tools` flag now
        // that the tool-call path is wired (runToolLoop below).
        return cfg.supportsTools();
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, java.util.function.Function<String, String> executor) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException(
                    "GenericOpenAICompatPool(" + cfg.providerKey() + "): unknown model=" + modelId);
        }
        acquireSlot();
        try { return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor); }
        finally { slots.release(); }
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson, java.util.function.Function<String, String> executor) {
        acquireSlot();
        try {
            int size = activeModels.size();
            int start = rrIndex.getAndIncrement() % size;
            RuntimeException last = null;
            for (int i = 0; i < size; i++) {
                String modelId = activeModels.get((start + i) % size);
                if (isModelBlocked(modelId)) {
                    last = new RuntimeException("model=" + modelId + " rate-limited");
                    continue;
                }
                try { return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor); }
                catch (RuntimeException e) {
                    last = e;
                    if (i < size - 1) log.warn(
                            "GenericOpenAICompatPool({}) tools: model={} failed, trying next: {}",
                            cfg.providerKey(), modelId, e.getMessage());
                }
            }
            throw last != null ? last : new RuntimeException("all models exhausted");
        } finally { slots.release(); }
    }

    @Override public boolean isModelBlocked(String modelId) {
        Long exp = rateLimitExpiry.get(modelId);
        return exp != null && System.currentTimeMillis() < exp;
    }

    @Override public boolean allBlocked() {
        if (activeModels.isEmpty()) return false;
        long now = System.currentTimeMillis();
        return activeModels.stream().allMatch(m -> {
            Long e = rateLimitExpiry.get(m);
            return e != null && now < e;
        });
    }

    @Override public Instant earliestRecovery() {
        long now = System.currentTimeMillis();
        return activeModels.stream()
                .map(rateLimitExpiry::get)
                .filter(e -> e != null && e > now)
                .min(Long::compareTo)
                .map(Instant::ofEpochMilli)
                .orElse(Instant.now());
    }

    @Override public String callSync(String prompt) {
        acquireSlot();
        try { return callWithFallback(prompt); }
        finally { slots.release(); }
    }

    @Override public void callStream(String prompt, Consumer<String> tokenConsumer) {
        // Phase 20.11 step 1 stub — stream path is a follow-up. Surface a
        // clear error so callers know not to route here.
        throw new UnsupportedOperationException(
                "GenericOpenAICompatPool(" + cfg.providerKey() + "): stream path not yet wired (Phase 20.11 follow-up)");
    }

    @Override public String callSyncOnModel(String modelId, String prompt) {
        if (!activeModels.contains(modelId)) {
            throw new IllegalArgumentException(
                    "GenericOpenAICompatPool(" + cfg.providerKey() + "): unknown model=" + modelId);
        }
        acquireSlot();
        try { return syncCallWithBackoff(modelId, prompt, cfg.contextWindow()); }
        finally { slots.release(); }
    }

    @Override public void callStreamOnModel(String modelId, String prompt, Consumer<String> tokenConsumer) {
        throw new UnsupportedOperationException(
                "GenericOpenAICompatPool(" + cfg.providerKey() + "): stream path not yet wired");
    }

    // ── Dispatch ───────────────────────────────────────────────────────────

    private String callWithFallback(String prompt) {
        int size = activeModels.size();
        if (size == 0) throw new IllegalStateException(
                "GenericOpenAICompatPool(" + cfg.providerKey() + "): no active models");
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            if (isModelBlocked(modelId)) {
                last = new RuntimeException("model=" + modelId + " currently rate-limited");
                continue;
            }
            try { return syncCallWithBackoff(modelId, prompt, cfg.contextWindow()); }
            catch (RuntimeException e) {
                last = e;
                if (i < size - 1) {
                    log.warn("GenericOpenAICompatPool({}): model={} failed, trying next: {}",
                            cfg.providerKey(), modelId, e.getMessage());
                }
            }
        }
        throw last != null ? last : new RuntimeException("all models exhausted");
    }

    private String syncCallWithBackoff(String modelId, String prompt, int tokenLimit) {
        long backoffMs = INITIAL_BACKOFF_MS;
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try { return syncCall(modelId, prompt, tokenLimit); }
            catch (RuntimeException e) {
                last = e;
                String msg = e.getMessage();
                if (msg != null && msg.contains("429") && attempt < MAX_RETRIES) {
                    long waitMs = parseRetryAfterMs(msg).orElse(backoffMs);
                    long expiry = System.currentTimeMillis() + waitMs;
                    rateLimitExpiry.merge(modelId, expiry, Math::max);
                    log.warn("GenericOpenAICompatPool({}): 429 model={} attempt={} retrying in {}ms",
                            cfg.providerKey(), modelId, attempt + 1, waitMs);
                    sleep(waitMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                } else { throw e; }
            }
        }
        throw last;
    }

    private String syncCall(String modelId, String prompt, int tokenLimit) {
        long estimate = estimateTokens(prompt, tokenLimit);
        boolean reserved = false;
        if (budgetGate != null) {
            budgetGate.tryAcquireOrThrow(cfg.providerKey(), modelId, estimate);
            reserved = true;
        }
        String outcome = "ok";
        String payload = buildPayload(modelId, prompt, tokenLimit);
        try {
            HttpRequest req = applyExtraHeaders(HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                outcome = "http_error";
                // Phase-18-style: stash retry-after on 429 from whichever source
                // this provider uses (header vs body).
                if (resp.statusCode() == 429) {
                    long waitMs = extractRetryAfterMs(resp).orElse(DEFAULT_BLOCK_MS);
                    // Phase 20.11 §migration — extend the block window when
                    // the upstream identifies an excluded backend.
                    waitMs = extendForExcludedBackend(modelId, resp.body(), waitMs);
                    // Phase 20.11 §migration (NVIDIA parity) — long-block
                    // classification on body substrings (e.g. "credit",
                    // "monthly", "quota exceeded").
                    waitMs = inferLongBlockMs(resp.body(), waitMs);
                    rateLimitExpiry.merge(modelId, System.currentTimeMillis() + waitMs, Math::max);
                    if (budgetGateMetrics != null) budgetGateMetrics.recordUpstream429(cfg.providerKey());
                } else if (resp.statusCode() == 404) {
                    // Phase 20.11 §migration (NVIDIA parity) — paid-tier 404
                    // permanent disable when a body marker matches.
                    checkPermanent404(modelId, resp.body());
                }
                throw new RuntimeException(cfg.providerKey() + " API error " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            // Phase 20.11 §migration — post-200 backend-blacklist check.
            checkBackendExclusion(modelId, root);
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) content = message.path("reasoning").asText("");
            JsonNode usage = root.path("usage");
            if (reserved) {
                budgetGate.correct(cfg.providerKey(), modelId, estimate, totalFromUsage(usage));
            } else {
                recordUsage(modelId, usage);
            }
            return content;
        } catch (RuntimeException e) {
            if ("ok".equals(outcome)) outcome = "io_error";
            throw e;
        } catch (Exception e) {
            outcome = "io_error";
            throw new RuntimeException(cfg.providerKey() + " sync call failed", e);
        } finally {
            if (callMetrics != null) {
                callMetrics.record(cfg.providerKey(), modelId, "sync", outcome);
            }
        }
    }

    // ── Tool-call loop (Phase 20.11 step 3) ────────────────────────────────

    /**
     * Mirrors the runToolLoop pattern from GroqPool / CerebrasPool /
     * NvidiaPool. Multi-turn conversation that accepts {@code tool_calls}
     * finish_reason and replays them through {@code executor}; closes
     * with the final synthesis (or empty content if the loop hit
     * MAX_TOOL_ROUNDS without a synthesis).
     *
     * Honors Phase 20.3 toolSchemaCachePolicy (omit tools spec on
     * rounds where the policy says so) and Phase 20.4 step 2b-2
     * per-paper token accumulator (via recordUsage). 429 handling
     * mirrors the sync path's rate-limit expiry write.
     */
    private com.emergentterrain.llm.ToolCallResponse runToolLoop(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, java.util.function.Function<String, String> executor) {
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = new java.util.ArrayList<>();
        try {
            ArrayNode messages = mapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isBlank())
                messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            JsonNode tools = mapper.readTree(toolsJson);

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                boolean lastRound = round == MAX_TOOL_ROUNDS;
                boolean sendTools = toolSchemaCachePolicy == null
                        ? !lastRound
                        : toolSchemaCachePolicy.shouldSendTools(round, lastRound);
                // Phase 20.1 — condense older tool-result messages before each
                // round's payload build. No-op when compressor isn't wired or
                // flag is off. Persisted trace via traceWriter is unaffected.
                if (toolResultCompressor != null) {
                    toolResultCompressor.condenseOlderToolMessages(messages);
                }
                // Phase 20.2 — tag-level history compaction (stacks after 20.1).
                if (conversationCompactor != null) {
                    conversationCompactor.compact(messages);
                }
                String payload = buildConversationPayload(
                        modelId, messages, sendTools ? tools : null, TOOL_MAX_TOKENS);
                long estimate = estimateTokens(payload, TOOL_MAX_TOKENS);
                boolean reserved = false;
                if (budgetGate != null) {
                    // Phase 21 §21.1 — first round goes through the HARD gate
                    // (same as a single-shot call); continuation rounds use SOFT
                    // so an in-flight tool conversation finishes gracefully
                    // instead of starting another round when the budget is
                    // already most-of-the-way full.
                    com.emergentterrain.llm.TpmBudgetGate.GateLevel level =
                            round == 0
                                    ? com.emergentterrain.llm.TpmBudgetGate.GateLevel.HARD
                                    : com.emergentterrain.llm.TpmBudgetGate.GateLevel.SOFT;
                    budgetGate.tryAcquireOrThrow(cfg.providerKey(), modelId, estimate, level);
                    reserved = true;
                }
                String roundOutcome = "ok";
                HttpResponse<String> resp;
                long t0 = System.currentTimeMillis();
                int respStatus = 0;
                String respBody = "";
                try {
                    HttpRequest req = applyExtraHeaders(HttpRequest.newBuilder()
                            .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                            .header("Authorization", "Bearer " + cfg.apiKey())
                            .header("Content-Type", "application/json"))
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .timeout(requestTimeout)
                            .build();
                    resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    respStatus = resp.statusCode();
                    respBody = resp.body();
                    if (resp.statusCode() != 200) {
                        roundOutcome = "http_error";
                        if (resp.statusCode() == 429) {
                            long waitMs = extractRetryAfterMs(resp).orElse(DEFAULT_BLOCK_MS);
                            waitMs = extendForExcludedBackend(modelId, resp.body(), waitMs);
                            waitMs = inferLongBlockMs(resp.body(), waitMs);
                            rateLimitExpiry.merge(modelId,
                                    System.currentTimeMillis() + waitMs, Math::max);
                            if (budgetGateMetrics != null) budgetGateMetrics.recordUpstream429(cfg.providerKey());
                        } else if (resp.statusCode() == 404) {
                            checkPermanent404(modelId, resp.body());
                        }
                        throw new RuntimeException(cfg.providerKey() + " API error "
                                + resp.statusCode() + ": " + resp.body());
                    }
                } catch (RuntimeException e) {
                    if ("ok".equals(roundOutcome)) roundOutcome = "io_error";
                    if (callMetrics != null)
                        callMetrics.record(cfg.providerKey(), modelId, "tools", roundOutcome);
                    if (traceWriter != null) {
                        traceWriter.record(LlmCallContext.currentPaperId(),
                                cfg.providerKey(), modelId, "tools", respStatus,
                                System.currentTimeMillis() - t0, payload, respBody);
                    }
                    throw e;
                } catch (Exception e) {
                    if (callMetrics != null)
                        callMetrics.record(cfg.providerKey(), modelId, "tools", "io_error");
                    if (traceWriter != null) {
                        traceWriter.record(LlmCallContext.currentPaperId(),
                                cfg.providerKey(), modelId, "tools", respStatus,
                                System.currentTimeMillis() - t0, payload, respBody);
                    }
                    throw new RuntimeException("tool loop io error", e);
                }
                if (callMetrics != null)
                    callMetrics.record(cfg.providerKey(), modelId, "tools", roundOutcome);
                if (traceWriter != null) {
                    traceWriter.record(LlmCallContext.currentPaperId(),
                            cfg.providerKey(), modelId, "tools", respStatus,
                            System.currentTimeMillis() - t0, payload, respBody);
                }

                JsonNode body    = mapper.readTree(resp.body());
                // Phase 20.11 §migration — post-200 backend-blacklist check.
                checkBackendExclusion(modelId, body);
                JsonNode choice  = body.path("choices").get(0);
                JsonNode message = choice.path("message");
                String   reason  = choice.path("finish_reason").asText("stop");
                JsonNode usage   = body.path("usage");
                if (reserved) {
                    budgetGate.correct(cfg.providerKey(), modelId, estimate, totalFromUsage(usage));
                } else {
                    recordUsage(modelId, usage);
                }

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
                        String harnessCallId = "call_" + (trace.size() + 1);
                        log.info("GenericOpenAICompatPool({}) tools: fn={} call_id={} result_len={}",
                                cfg.providerKey(), fnName, harnessCallId, result.length());
                        trace.add(new com.emergentterrain.llm.ToolInvocation(
                                fnName, fnArgs, result, round, harnessCallId));
                        messages.addObject()
                                .put("role", "tool")
                                .put("tool_call_id", providerCallId)
                                .put("content", "[" + harnessCallId + "] " + result);
                    }
                } else {
                    String content = message.path("content").asText("");
                    if (content.isBlank()) content = message.path("reasoning").asText("");
                    if (!trace.isEmpty()) {
                        log.info("GenericOpenAICompatPool({}) tools: loop closed model={} calls={} rounds=[{}..{}]",
                                cfg.providerKey(), modelId, trace.size(),
                                trace.get(0).round(), trace.get(trace.size() - 1).round());
                    }
                    return new com.emergentterrain.llm.ToolCallResponse(
                            content, java.util.List.copyOf(trace));
                }
            }
            return new com.emergentterrain.llm.ToolCallResponse("", java.util.List.copyOf(trace));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GenericOpenAICompatPool(" + cfg.providerKey()
                    + ") tool loop failed", e);
        }
    }

    private String buildConversationPayload(String modelId, JsonNode messages,
                                             JsonNode tools, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", false);
            node.put(cfg.maxTokensField(), tokenLimit);
            node.put("temperature",
                    LlmCallContext.currentTemperature().orElse(0.7));
            if (LlmCallContext.isJsonOutputMode()) {
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

    private String buildPayload(String modelId, String prompt, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", false);
            node.put(cfg.maxTokensField(), tokenLimit);
            node.put("temperature",
                    LlmCallContext.currentTemperature().orElse(0.7));
            if (LlmCallContext.isJsonOutputMode()) {
                node.putObject("response_format").put("type", "json_object");
            }
            ArrayNode messages = node.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build payload", e);
        }
    }

    /**
     * Phase 20.11 §migration — attach any provider-declared
     * {@code extraHeaders} (e.g. OpenRouter's HTTP-Referer / X-Title) to
     * the request builder. Skips entries with null or blank keys/values
     * defensively so a typo in the YAML doesn't crash the request build.
     * Returns the same builder so the call chain stays fluent at both
     * call sites.
     */
    private HttpRequest.Builder applyExtraHeaders(HttpRequest.Builder b) {
        java.util.Map<String, String> headers = cfg.extraHeaders();
        if (headers == null || headers.isEmpty()) return b;
        for (var e : headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isBlank() || v == null) continue;
            b = b.header(k, v);
        }
        return b;
    }

    /**
     * Phase 20.11 §migration — post-200 backend-blacklist check.
     * When a successful response carries {@code body.provider} in
     * {@link ProviderConfig#excludedBackends()}, block the model for
     * 5 minutes to force re-routing on the next attempt. No-op when
     * the excluded set is empty or the response carries no
     * {@code provider} field (most providers don't surface it). Mirrors
     * OpenRouterPool's {@code checkBackendExclusion} semantics so a
     * YAML-flipped OpenRouter is bit-identical to the legacy pool.
     */
    private void checkBackendExclusion(String modelId, JsonNode body) {
        java.util.Set<String> excluded = cfg.excludedBackends();
        if (excluded == null || excluded.isEmpty()) return;
        if (body == null || body.isMissingNode()) return;
        String backend = body.path("provider").asText("");
        if (!backend.isBlank() && excluded.contains(backend)) {
            log.warn("GenericOpenAICompatPool({}): model={} routed via excluded backend={} — "
                            + "blocking model for 5 min to force re-route",
                    cfg.providerKey(), modelId, backend);
            rateLimitExpiry.put(modelId, System.currentTimeMillis() + 5 * 60 * 1000L);
        }
    }

    /**
     * Phase 20.11 §migration (NVIDIA parity) — permanent-disable check on
     * a 404 response. When the body contains any substring from
     * {@link ProviderConfig#permanent404Markers()}, set the model's
     * block expiry to {@link Long#MAX_VALUE} so it never participates in
     * round-robin again. Mirrors {@code NvidiaPool.paidTierModels}
     * semantics ({@code "Not found for account"} = free-tier account
     * lacks entitlement; permanent disable). No-op when the marker set
     * is empty.
     */
    private void checkPermanent404(String modelId, String body) {
        java.util.Set<String> markers = cfg.permanent404Markers();
        if (markers == null || markers.isEmpty()) return;
        if (body == null || body.isBlank()) return;
        for (String marker : markers) {
            if (marker == null || marker.isBlank()) continue;
            if (body.contains(marker)) {
                log.warn("GenericOpenAICompatPool({}): model={} 404 matched permanent-disable marker=\"{}\" — "
                                + "model permanently disabled",
                        cfg.providerKey(), modelId, marker);
                rateLimitExpiry.put(modelId, Long.MAX_VALUE);
                return;
            }
        }
    }

    /**
     * Phase 20.11 §migration (NVIDIA parity) — long-block classification
     * on a 429 body. When the body matches any substring in
     * {@link ProviderConfig#longBlockBodyMarkers()} AND
     * {@link ProviderConfig#longBlockMs()} {@code > 0}, return at least
     * {@code longBlockMs} regardless of the upstream retry-after.
     * Mirrors {@code NvidiaPool.inferBlockMsFromBody}: monthly-credits
     * exhaustion needs a 24-hour block, not the 60-second default, so
     * the next billing cycle gets a chance to renew before the next
     * attempt. Returns the (possibly extended) wait window in ms.
     */
    private long inferLongBlockMs(String body, long baseWaitMs) {
        java.util.Set<String> markers = cfg.longBlockBodyMarkers();
        long longMs = cfg.longBlockMs();
        if (markers == null || markers.isEmpty() || longMs <= 0) return baseWaitMs;
        if (body == null || body.isBlank()) return baseWaitMs;
        String lower = body.toLowerCase();
        for (String marker : markers) {
            if (marker == null || marker.isBlank()) continue;
            if (lower.contains(marker.toLowerCase())) {
                long extended = Math.max(baseWaitMs, longMs);
                log.warn("GenericOpenAICompatPool({}): 429 body matched long-block marker=\"{}\" — "
                                + "block extended to {}ms",
                        cfg.providerKey(), marker, extended);
                return extended;
            }
        }
        return baseWaitMs;
    }

    /**
     * Phase 20.11 §migration — 429 block-window extension when the
     * upstream identifies a backend in {@link ProviderConfig#excludedBackends()}.
     * Parses {@code error.metadata.provider_name} from the body and, if
     * matched, bumps the block window to at least 5 minutes (covers
     * backends with global free-tier caps like OpenRouter's Venice).
     * Returns the (possibly extended) wait window in milliseconds.
     */
    private long extendForExcludedBackend(String modelId, String body, long baseWaitMs) {
        java.util.Set<String> excluded = cfg.excludedBackends();
        if (excluded == null || excluded.isEmpty()) return baseWaitMs;
        if (body == null || body.isBlank()) return baseWaitMs;
        try {
            JsonNode root = mapper.readTree(body);
            String backend = root.path("error").path("metadata").path("provider_name").asText("");
            if (!backend.isBlank() && excluded.contains(backend)) {
                long extended = Math.max(baseWaitMs, 5 * 60 * 1000L);
                log.warn("GenericOpenAICompatPool({}): model={} 429 backend={} (excluded) — "
                                + "block extended to {}ms",
                        cfg.providerKey(), modelId, backend, extended);
                return extended;
            }
        } catch (Exception ignored) {
            // Body wasn't JSON; fall through to the base window.
        }
        return baseWaitMs;
    }

    // ── Retry-after parsing ────────────────────────────────────────────────

    private OptionalLong extractRetryAfterMs(HttpResponse<String> resp) {
        if (cfg.retryAfterSource() == ProviderConfig.RetryAfterSource.HEADER) {
            var header = resp.headers().firstValue("Retry-After");
            if (header.isPresent()) return OptionalLong.of(parseHeaderRetryAfter(header.get()));
        }
        return parseRetryAfterMs(resp.body());
    }

    private long parseHeaderRetryAfter(String value) {
        try { return Long.parseLong(value.trim()) * 1000L; }
        catch (NumberFormatException ignored) { return DEFAULT_BLOCK_MS; }
    }

    /** Parses Groq-style "(Xh)?(Ym)?Z.Ws" and bare "X.Ys" from an error body. */
    OptionalLong parseRetryAfterMs(String message) {
        if (message == null) return OptionalLong.empty();
        Matcher mm = Pattern.compile("(?:(\\d+)h)?(\\d+)m(\\d+\\.?\\d*)s").matcher(message);
        if (mm.find()) {
            try {
                long h = mm.group(1) != null ? Long.parseLong(mm.group(1)) : 0;
                long ms = h * 3_600_000
                        + Long.parseLong(mm.group(2)) * 60_000
                        + (long)(Double.parseDouble(mm.group(3)) * 1000) + 500;
                return OptionalLong.of(ms);
            } catch (NumberFormatException ignored) {}
        }
        Matcher ms = Pattern.compile("try again in (\\d+\\.?\\d*)s").matcher(message);
        if (ms.find()) {
            try { return OptionalLong.of((long)(Double.parseDouble(ms.group(1)) * 1000) + 500); }
            catch (NumberFormatException ignored) {}
        }
        Matcher bare = Pattern.compile("\\b(\\d+\\.?\\d*)\\s*s\\b").matcher(message);
        if (bare.find()) {
            try {
                double secs = Double.parseDouble(bare.group(1));
                if (secs > 0 && secs < 3600) return OptionalLong.of((long)(secs * 1000) + 500);
            } catch (NumberFormatException ignored) {}
        }
        return OptionalLong.empty();
    }

    // ── Token + budget accounting ──────────────────────────────────────────

    private void recordUsage(String model, JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt + completion <= 0) return;
        // Phase 20.4 step 2b-2 — per-paper accumulator (no-op outside scope).
        LlmCallContext.recordTokenUsage(prompt, completion);
        if (tokenAccountant != null) {
            tokenAccountant.record(new TokenUsage(
                    cfg.providerKey(), model, prompt, completion, Instant.now()));
        }
    }

    private long totalFromUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return 0L;
        return usage.path("prompt_tokens").asLong(0) + usage.path("completion_tokens").asLong(0);
    }

    /** Conservative pre-debit estimate: rough chars-per-token plus the output cap. */
    private long estimateTokens(String prompt, int tokenLimit) {
        return Math.max(1, prompt.length() / 4) + tokenLimit;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void acquireSlot() {
        try { slots.acquire(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted acquiring slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while sleeping", e);
        }
    }
}
