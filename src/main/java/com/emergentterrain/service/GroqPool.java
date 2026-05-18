package com.emergentterrain.service;

import com.emergentterrain.llm.BudgetExceededException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Groq-backed LlmPool. At startup, fetches the live model list from the Groq /models
 * endpoint, runs a two-phase evaluation per model (peer ranking + hypothesis-falsification
 * test), scores test responses via Ollama, combines Borda count and Ollama quality into a
 * final score, and selects the top-N models (app.ai.groq.primaryModelCount, default 2).
 * Runtime calls fall back through the ranked list on error.
 *
 * If Groq is unreachable at startup the application exits immediately with a clear message.
 * Active when app.ai.groq.enabled=true.
 * Set app.ai.groq.preferredModels (comma-separated) to skip discovery and pin models.
 */
@Component
@ConditionalOnProperty(name = "app.ai.groq.enabled", havingValue = "true")
public class GroqPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(GroqPool.class);

    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private static final Set<String> EXCLUDED_SUBSTRINGS = Set.of(
            "whisper", "prompt-guard", "orpheus", "safeguard");

    // Only consider models at or above this parameter threshold (MoE models are
    // sized by their per-token active parameters, e.g. 8x7b is treated as 7B)
    private static final int MIN_MODEL_SIZE_B = 70;

    private static final long INTER_CALL_DELAY_MS  = 1500; // between models
    private static final long INTRA_CALL_DELAY_MS  = 2000; // between ranking and test for same model
    private static final int  MAX_RETRIES          = 4;
    /** Phase 9 follow-on (2026-05-14) — bumped 2s → 10s so when
     *  parseRetryAfterMs can't extract the provider's actual retry-after, the
     *  fallback wait is long enough to clear the typical free-tier per-minute
     *  window (~30s avg). With 2s, retries fired well inside the same TPM
     *  window and re-hit 429 every time. */
    private static final long INITIAL_BACKOFF_MS   = 10_000;

    // Reasoning models (e.g. openai/gpt-oss-120b) consume most of their token budget on
    // internal chain-of-thought before writing content. 150 was exhausted by reasoning alone.
    private static final int RANKING_MAX_TOKENS = 600;

    // Hypothesis-falsification test — correct answer: REFUTED, critical angle ~25 degrees
    private static final String TEST_PROMPT =
            "A physics simulation computed pendulum period errors (L=0.85 m, T0=1.846 s):\n" +
            "5deg->0.05%  10deg->0.19%  15deg->0.43%  20deg->0.77%  25deg->1.20%  30deg->1.74%\n\n" +
            "Hypothesis: The small-angle approximation error exceeds 1% below theta=20 degrees.\n\n" +
            "Assess this hypothesis. Reply with ONLY this JSON, no other text:\n" +
            "{\"verdict\":\"SUPPORTED or REFUTED or PARTIAL\"," +
            "\"critical_angle_deg\":<integer>," +
            "\"reasoning\":\"one concise sentence citing the specific data\"}";

    // Max Ollama score per model: verdict(10) + angle(10) + reasoning(10)
    private static final int MAX_OLLAMA_SCORE = 30;

    private static final int MAX_TOOL_ROUNDS = 5;

    private final String apiKey;
    private final int maxTokens;
    private final int toolMaxTokens;
    private final String ollamaJudgeUrl;
    private final String ollamaJudgeModel;
    private final java.util.concurrent.CopyOnWriteArrayList<String> activeModels;
    /**
     * Phase 9 U7 — Groq's sub-70B chat models that passed selection probes.
     * Exposed via {@link #fastTierModelIds()} so {@code FastModelRegistry}
     * picks them up alongside Ollama. Stays empty when no Groq sub-70B model
     * passed (or when {@code preferredModels} pinned only primary-tier ids).
     */
    private final java.util.concurrent.CopyOnWriteArrayList<String> fastTierActiveModels;
    /**
     * Phase 9 follow-on (2026-05-14) — primary-tier candidates that failed
     * the initial selection test (chat / JSON / tool probe). Re-tested every
     * {@code app.ai.selection-tests.retestIntervalMinutes} by
     * {@code ModelRetestService}; on a passing re-probe they move into
     * {@link #activeModels}. Treated as blocked by {@link #isModelBlocked}
     * so the registry doesn't route to them between probes.
     */
    private final java.util.Set<String> droppedPrimaryModels =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Phase 9 follow-on — fast-tier counterpart to {@link #droppedPrimaryModels}.
     * Same lifecycle, but the re-probe runs chat + JSON only (no tool probe,
     * matching the initial fast-tier selection contract).
     */
    private final java.util.Set<String> droppedFastTierModels =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** Phase 9 follow-on — per-request HTTP read timeout, driven by
     *  {@code app.ai.http.requestTimeoutSeconds}. A response that never
     *  arrives surfaces as HttpTimeoutException after this window, which the
     *  retry/fallthrough logic translates into a fallthrough to the next
     *  model / registry handle. The client-level connectTimeout only covers
     *  connection setup. */
    private final Duration requestTimeout;
    // Per-model TPD expiry: when a model exhausts its daily token quota, record when it recovers.
    // Models are skipped until System.currentTimeMillis() passes the expiry epoch.
    private final ConcurrentHashMap<String, Long> tpdExpiry = new ConcurrentHashMap<>();
    /**
     * Phase 9 follow-on (2026-05-14) — per-model per-MINUTE expiry. Distinct
     * from {@link #tpdExpiry} (per-day). Set on every TPM 429 from the parsed
     * retry-after window; consulted by {@link #isModelBlocked(String)} so
     * concurrent threads skip the blocked model immediately rather than
     * racing past the check and firing their own 429s.
     */
    private final ConcurrentHashMap<String, Long> tpmExpiry = new ConcurrentHashMap<>();

    private static final String PROVIDER_ID = "groq";

    // Reports every completed Groq call's token spend so SimulationGate can budget.
    // Optional: null when running tests that don't wire the accountant bean.
    @Autowired(required = false)
    private TokenAccountant tokenAccountant;

    @Autowired(required = false)
    private LlmCallMetrics callMetrics;

    @Autowired(required = false)
    private com.emergentterrain.llm.LlmTraceWriter traceWriter;

    /**
     * Phase 9 G9 — pre-debit budget gate. When wired, every HTTP call to Groq
     * reserves its conservative token estimate BEFORE sending the request, so
     * concurrent virtual threads cannot collectively overshoot the per-minute
     * budget (observed at 172% in the research/a3289005 logs). Optional:
     * null in tests; legacy post-spend {@link #recordUsage} runs as a fallback
     * when this is null.
     */
    @Autowired(required = false)
    private TpmBudgetGate budgetGate;

    public GroqPool(
            @Value("${app.ai.groq.apiKey:}") String apiKey,
            @Value("${app.ai.groq.parallelism:3}") int parallelism,
            @Value("${app.ai.groq.maxTokens:600}") int maxTokens,
            @Value("${app.ai.groq.toolMaxTokens:1500}") int toolMaxTokens,
            @Value("${app.ai.groq.primaryModelCount:10}") int primaryModelCount,
            @Value("${app.ai.groq.preferredModels:}") String preferredModels,
            @Value("${app.ai.ollamaJudgeUrl:http://localhost:11434}") String ollamaJudgeUrl,
            @Value("${app.ai.ollamaJudgeModel:gemma3:4b}") String ollamaJudgeModel,
            @Value("${app.ai.selection-tests.enabled:true}") boolean selectionTestsEnabled,
            @Value("${app.ai.http.requestTimeoutSeconds:420}") int requestTimeoutSeconds,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GroqPool: app.ai.groq.apiKey is required when app.ai.groq.enabled=true");
        }
        this.apiKey          = apiKey;
        this.maxTokens       = maxTokens;
        this.toolMaxTokens   = toolMaxTokens;
        this.ollamaJudgeUrl  = ollamaJudgeUrl;
        this.ollamaJudgeModel = ollamaJudgeModel;
        this.mapper          = mapper;
        this.slots           = new Semaphore(parallelism);
        this.requestTimeout  = Duration.ofSeconds(requestTimeoutSeconds);
        this.http            = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        List<String> primaryCandidates;
        List<String> fastCandidates;
        if (preferredModels != null && !preferredModels.isBlank()) {
            // Operator pinned a specific list — treat as primary, no auto fast-tier classification.
            primaryCandidates = List.of(preferredModels.split(","))
                    .stream().map(String::trim).filter(s -> !s.isBlank()).toList();
            fastCandidates = List.of();
            log.info("GroqPool: using configured models={} parallelism={}", primaryCandidates, parallelism);
        } else {
            // Phase 9 U7 — discoverAndRankModels returns primary (70B+) and stashes
            // sub-70B chat models for fast-tier consideration.
            primaryCandidates = discoverAndRankModels(primaryModelCount);
            fastCandidates = List.copyOf(pendingFastTierCandidates);
            pendingFastTierCandidates.clear();
            log.info("GroqPool: selected primary models={} parallelism={} fast-tier candidates={}",
                    primaryCandidates, parallelism, fastCandidates);
        }

        // Phase 9 U5b — universal selection tests for primary tier (chat + JSON + tool).
        List<String> primaryPassed = selectionTestsEnabled
                ? ModelSelectionTester.filterPassing(
                        PROVIDER_ID, primaryCandidates,
                        (mid, prompt) -> syncCallWithBackoff(mid, prompt, maxTokens),
                        mid -> (sys, user, toolsJson, exec) ->
                                runToolLoop(mid, sys, user, toolsJson, exec))
                : primaryCandidates;
        this.activeModels = new java.util.concurrent.CopyOnWriteArrayList<>(primaryPassed);
        // Phase 9 follow-on — stash primaries that failed for later re-test.
        if (selectionTestsEnabled) {
            for (String c : primaryCandidates) {
                if (!primaryPassed.contains(c)) droppedPrimaryModels.add(c);
            }
        }

        // Phase 9 U7 — fast-tier selection tests (chat + JSON only; no tool
        // probe since sub-70B models aren't expected to do tool-call work).
        List<String> fastPassed = selectionTestsEnabled && !fastCandidates.isEmpty()
                ? ModelSelectionTester.filterPassing(
                        PROVIDER_ID + "[fast]", fastCandidates,
                        (mid, prompt) -> syncCallWithBackoff(mid, prompt, maxTokens),
                        null)  // null toolDispatch → chat + JSON only
                : fastCandidates;
        this.fastTierActiveModels = new java.util.concurrent.CopyOnWriteArrayList<>(fastPassed);
        if (selectionTestsEnabled) {
            for (String c : fastCandidates) {
                if (!fastPassed.contains(c)) droppedFastTierModels.add(c);
            }
        }

        log.info("GroqPool: primary activeModels={} (dropped-awaiting-retest={}) "
                        + "fastTier activeModels={} (dropped-awaiting-retest={}) selection-tests={}",
                activeModels, droppedPrimaryModels,
                fastTierActiveModels, droppedFastTierModels,
                selectionTestsEnabled ? "on" : "off");
    }

    /**
     * Phase 9 U7 — populated by {@link #discoverAndRankModels} when it classifies
     * a candidate as sub-70B chat. Drained into {@link #fastTierActiveModels}
     * at construction (just after primary classification finishes). Field-level
     * for lifecycle reasons — discoverAndRankModels is called from the constructor
     * before final assignment.
     */
    private final List<String> pendingFastTierCandidates = new ArrayList<>();

    // --- LlmPool ---

    @Override
    public String callSync(String prompt) {
        acquireSlot();
        try {
            return callSyncWithFallback(prompt);
        } finally {
            slots.release();
        }
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        acquireSlot();
        try {
            callStreamWithFallback(prompt, tokenConsumer);
        } finally {
            slots.release();
        }
    }

    @Override public String selectedModel()        { return activeModels.get(0); }
    @Override public List<String> selectedModels() { return activeModels; }
    @Override public int backendCount()            { return 1; }
    @Override public int healthyCount()            { return 1; }

    // Phase 7 E1.0 — LlmPool provider-pluralised surface
    @Override public String providerKey()          { return "groq"; }
    @Override public boolean supportsTools()       { return true; }
    @Override public boolean allBlocked()          { return allModelsTpdBlocked(); }

    // Phase 9 U1 — per-model entry points so the unified registry can target
    // a specific Groq model rather than letting our round-robin pick.

    /**
     * Phase 9 U1 + 2026-05-14 follow-on — primary handles aggregated by
     * {@code PrimaryModelRegistry}. Includes models awaiting re-test (so the
     * registry can route to them the moment {@link #onModelRecovered} fires)
     * along with currently-active models. Both kinds report
     * {@link #isModelBlocked} truthfully so the registry filters dropped
     * models out of dispatch.
     */
    @Override public List<String> modelIds() {
        List<String> out = new ArrayList<>(activeModels.size() + droppedPrimaryModels.size());
        out.addAll(activeModels);
        out.addAll(droppedPrimaryModels);
        return List.copyOf(out);
    }
    /** Phase 9 U7 — sub-70B Groq chat models exposed to {@code FastModelRegistry}. */
    @Override public List<String> fastTierModelIds() {
        List<String> out = new ArrayList<>(
                fastTierActiveModels.size() + droppedFastTierModels.size());
        out.addAll(fastTierActiveModels);
        out.addAll(droppedFastTierModels);
        return List.copyOf(out);
    }

    @Override public boolean isModelBlocked(String modelId) {
        // Phase 9 follow-on — drop-and-retest models stay blocked until they recover.
        if (droppedPrimaryModels.contains(modelId)
                || droppedFastTierModels.contains(modelId)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long tpd = tpdExpiry.get(modelId);
        if (tpd != null && now < tpd) return true;
        Long tpm = tpmExpiry.get(modelId);
        return tpm != null && now < tpm;
    }

    /** Phase 9 U7 — accept both primary and fast-tier model ids. */
    private boolean knowsModel(String modelId) {
        return activeModels.contains(modelId) || fastTierActiveModels.contains(modelId);
    }

    @Override
    public List<RetestableModel> pendingRetests() {
        if (droppedPrimaryModels.isEmpty() && droppedFastTierModels.isEmpty()) {
            return List.of();
        }
        List<RetestableModel> out = new ArrayList<>();
        // Primary: chat + JSON + tool probe.
        for (String mid : droppedPrimaryModels) {
            out.add(new RetestableModel(
                    mid,
                    (m, prompt) -> syncCallWithBackoff(m, prompt, maxTokens),
                    (sys, user, toolsJson, exec) ->
                            runToolLoop(mid, sys, user, toolsJson, exec)));
        }
        // Fast tier: chat + JSON only (no tool probe — matches initial selection).
        for (String mid : droppedFastTierModels) {
            out.add(new RetestableModel(
                    mid,
                    (m, prompt) -> syncCallWithBackoff(m, prompt, maxTokens),
                    null));
        }
        return out;
    }

    @Override
    public void onModelRecovered(String modelId) {
        // Order: add to active before remove from dropped so concurrent readers
        // see the model in at least one set at all times.
        if (droppedPrimaryModels.contains(modelId)) {
            if (!activeModels.contains(modelId)) activeModels.add(modelId);
            droppedPrimaryModels.remove(modelId);
        } else if (droppedFastTierModels.contains(modelId)) {
            if (!fastTierActiveModels.contains(modelId)) fastTierActiveModels.add(modelId);
            droppedFastTierModels.remove(modelId);
        }
    }

    @Override public String callSyncOnModel(String modelId, String prompt) {
        if (!knowsModel(modelId)) {
            throw new IllegalArgumentException("GroqPool: unknown model=" + modelId);
        }
        acquireSlot();
        try {
            return syncCallWithBackoff(modelId, prompt, maxTokens);
        } finally {
            slots.release();
        }
    }

    @Override public void callStreamOnModel(String modelId, String prompt,
                                              java.util.function.Consumer<String> tokenConsumer) {
        if (!knowsModel(modelId)) {
            throw new IllegalArgumentException("GroqPool: unknown model=" + modelId);
        }
        acquireSlot();
        try {
            streamCall(modelId, prompt, tokenConsumer);
        } finally {
            slots.release();
        }
    }

    @Override public com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, java.util.function.Function<String, String> executor) {
        if (!knowsModel(modelId)) {
            throw new IllegalArgumentException("GroqPool: unknown model=" + modelId);
        }
        acquireSlot();
        try {
            return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor);
        } finally {
            slots.release();
        }
    }

    /** True when every model in the active list has an unexpired TPD block. */
    public boolean allModelsTpdBlocked() {
        if (activeModels.isEmpty()) return false;
        long now = System.currentTimeMillis();
        return activeModels.stream().allMatch(m -> {
            Long exp = tpdExpiry.get(m);
            return exp != null && now < exp;
        });
    }

    /** Epoch-millis of the earliest TPD expiry across all active models. */
    @Override
    public java.time.Instant earliestRecovery() {
        long now = System.currentTimeMillis();
        return activeModels.stream()
                .map(tpdExpiry::get)
                .filter(exp -> exp != null && exp > now)
                .min(Long::compareTo)
                .map(java.time.Instant::ofEpochMilli)
                .orElse(java.time.Instant.now());
    }

    /**
     * Multi-turn tool-calling call. The professor LLM may invoke tools up to
     * MAX_TOOL_ROUNDS times; each call is executed via {@code executor} and the
     * result added to the conversation. Returns the model's final synthesis text.
     */
    @Override
    public com.emergentterrain.llm.ToolCallResponse callWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson,
            java.util.function.Function<String, String> executor) {
        acquireSlot();
        try {
            int size  = activeModels.size();
            int start = rrIndex.getAndIncrement() % size;
            RuntimeException last = null;
            for (int i = 0; i < size; i++) {
                String modelId = activeModels.get((start + i) % size);
                Long exp = tpdExpiry.get(modelId);
                if (exp != null && System.currentTimeMillis() < exp) {
                    last = new RuntimeException("TPD quota not yet recovered for model=" + modelId);
                    continue;
                }
                try {
                    return runToolLoop(modelId, systemPrompt, userPrompt, toolsJson, executor);
                } catch (RuntimeException e) {
                    last = e;
                    if (i < size - 1)
                        log.warn("GroqPool tools: model={} failed, trying next: {}", modelId, e.getMessage());
                }
            }
            throw last;
        } finally {
            slots.release();
        }
    }

    private com.emergentterrain.llm.ToolCallResponse runToolLoop(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson,
            java.util.function.Function<String, String> executor) {
        // Phase 7 F5.2 — local capture of every tool call the LLM issued in this
        // loop, returned to the caller as Tier 2 provenance.
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.node.ArrayNode messages = mapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isBlank())
                messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            JsonNode tools = mapper.readTree(toolsJson);

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                boolean lastRound = round == MAX_TOOL_ROUNDS;
                String payload = buildConversationPayload(
                        modelId, messages, lastRound ? null : tools, toolMaxTokens);
                // Phase 9 G9 — pre-debit per tool-loop round. The conversation
                // grows each round as tool messages accumulate, so the estimate
                // is recomputed from the current payload size (a reasonable
                // proxy for prompt token count) plus the per-round max output.
                long estimate = estimateTokens(payload, toolMaxTokens);
                boolean reserved = false;
                if (budgetGate != null) {
                    budgetGate.tryAcquireOrThrow(PROVIDER_ID, modelId, estimate);
                    reserved = true;
                }
                String roundOutcome = "ok";
                HttpResponse<String> resp;
                long t0 = System.currentTimeMillis();
                int respStatus = 0;
                String respBody = "";
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/chat/completions"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .timeout(requestTimeout)
                            .build();
                    resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    respStatus = resp.statusCode();
                    respBody = resp.body();
                    if (resp.statusCode() != 200) {
                        roundOutcome = "http_error";
                        throw new RuntimeException("Groq API error " + resp.statusCode() + ": " + resp.body());
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
                JsonNode usage   = body.path("usage");
                if (reserved) {
                    budgetGate.correct(PROVIDER_ID, modelId, estimate, totalFromUsage(usage));
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
                        // Phase 11 C1 — stable harness-assigned ID, monotonic across the
                        // trace. The LLM sees this prefixed on the tool result so it can
                        // cite the call as [call_N] in its synthesis. Citation validator
                        // looks up these IDs in the persisted trace.
                        String harnessCallId = "call_" + (trace.size() + 1);
                        log.info("GroqPool tools: fn={} call_id={} result_len={}",
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
                    if (content.isBlank()) content = message.path("reasoning").asText("");
                    if (!trace.isEmpty()) {
                        log.info("GroqPool tools: loop closed model={} calls={} rounds=[{}..{}]",
                                modelId, trace.size(),
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
            throw new RuntimeException("GroqPool tool loop failed", e);
        }
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

    // --- Runtime fallback wrappers ---

    private String callSyncWithFallback(String prompt) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            Long exp = tpdExpiry.get(modelId);
            if (exp != null && System.currentTimeMillis() < exp) {
                last = new RuntimeException("TPD quota not yet recovered for model=" + modelId);
                continue;
            }
            try {
                return syncCall(modelId, prompt, maxTokens);
            } catch (RuntimeException e) {
                last = e;
                int next = (start + i + 1) % size;
                if (i < size - 1) {
                    log.warn("GroqPool: model={} failed, trying next model={}: {}",
                            modelId, activeModels.get(next), e.getMessage());
                }
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
            Long exp = tpdExpiry.get(modelId);
            if (exp != null && System.currentTimeMillis() < exp) {
                last = new RuntimeException("TPD quota not yet recovered for model=" + modelId);
                continue;
            }
            try {
                streamCall(modelId, prompt, tokenConsumer);
                return;
            } catch (RuntimeException e) {
                last = e;
                int next = (start + i + 1) % size;
                if (i < size - 1) {
                    log.warn("GroqPool: model={} stream failed, trying next model={}: {}",
                            modelId, activeModels.get(next), e.getMessage());
                }
            }
        }
        throw last;
    }

    // --- Model discovery: peer ranking + hypothesis test + Ollama scoring ---

    private List<String> discoverAndRankModels(int primaryModelCount) {
        List<String> candidates = fetchCandidateModels();
        int n = candidates.size();
        log.info("GroqPool: evaluating {} models (live list): {}", n, candidates);

        String rankingPrompt = buildRankingPrompt(candidates);
        Map<String, Integer> bordaScores  = new HashMap<>();
        Map<String, String>  testResponses = new LinkedHashMap<>();
        for (String c : candidates) bordaScores.put(c, 0);

        for (int i = 0; i < n; i++) {
            String modelId = candidates.get(i);
            if (i > 0) sleep(INTER_CALL_DELAY_MS);

            // Phase 1: ranking call — low token budget, just a JSON array of names
            try {
                String rankResponse = syncCallWithBackoff(modelId, rankingPrompt, RANKING_MAX_TOKENS);
                List<String> ranking = parseRanking(mapper, rankResponse, candidates);
                for (int rank = 0; rank < ranking.size(); rank++) {
                    bordaScores.merge(ranking.get(rank), n - 1 - rank, Integer::sum);
                }
                log.info("GroqPool ranking: rater={} ranked {}/{}", modelId, ranking.size(), n);
            } catch (Exception e) {
                log.warn("GroqPool ranking skipped rater={}: {}", modelId, e.getMessage());
            }

            // Phase 2: hypothesis-falsification test — full token budget, quality matters
            sleep(INTRA_CALL_DELAY_MS);
            try {
                String testResponse = syncCallWithBackoff(modelId, TEST_PROMPT, maxTokens);
                testResponses.put(modelId, testResponse);
                log.info("GroqPool test: model={} responded len={}", modelId, testResponse.length());
            } catch (Exception e) {
                log.warn("GroqPool test skipped model={}: {}", modelId, e.getMessage());
            }
        }

        // Phase 3: Ollama scores all test responses as neutral judge
        Map<String, Integer> ollamaScores = Collections.emptyMap();
        if (!testResponses.isEmpty()) {
            try {
                ollamaScores = scoreWithOllama(testResponses, candidates);
                log.info("GroqPool: Ollama quality scores={}", ollamaScores);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("GroqPool: Ollama scoring failed — using Borda only: {} ({})",
                        e.getMessage(), cause.getMessage());
            }
        }

        // Combine: 50% normalised Borda + 50% normalised Ollama score
        int maxBorda = n > 1 ? n * (n - 1) : 1;
        Map<String, Integer> finalOllama = ollamaScores;

        List<String> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare(combined(b, bordaScores, finalOllama, maxBorda),
                                                  combined(a, bordaScores, finalOllama, maxBorda)))
                .collect(Collectors.toList());

        // Log full breakdown
        log.info("GroqPool evaluation breakdown (borda/ollama/combined):");
        for (String m : ranked) {
            double nb = (double) bordaScores.getOrDefault(m, 0) / maxBorda;
            double no = (double) finalOllama.getOrDefault(m, 0) / MAX_OLLAMA_SCORE;
            log.info("  {} borda={}({})  ollama={}({})  combined={}",
                    m,
                    bordaScores.getOrDefault(m, 0), String.format("%.2f", nb),
                    finalOllama.getOrDefault(m, 0),  String.format("%.2f", no),
                    String.format("%.3f", 0.5 * nb + 0.5 * no));
        }

        int take = Math.min(primaryModelCount, ranked.size());
        List<String> selected = Collections.unmodifiableList(ranked.subList(0, take));
        log.info("GroqPool: top{}={}", take, selected);
        return selected;
    }

    private double combined(String m, Map<String, Integer> borda,
                            Map<String, Integer> ollama, int maxBorda) {
        return 0.5 * borda.getOrDefault(m, 0) / maxBorda
             + 0.5 * ollama.getOrDefault(m, 0) / MAX_OLLAMA_SCORE;
    }

    // --- Ranking prompt and parser ---

    private String buildRankingPrompt(List<String> candidates) {
        return "You are selecting AI models for a scientific research simulation covering " +
               "physics, mathematics, and materials science.\n" +
               "Rank ALL models below from best (first) to worst (last) for scientific " +
               "reasoning and mathematical rigor. Every model must appear exactly once — no ties.\n\n" +
               "Models: " + String.join(", ", candidates) + "\n\n" +
               "Reply with ONLY a JSON array of model names in ranked order, best first. " +
               "Use exact model names. No other text:\n" +
               "[\"best-model\", \"second-model\", ...]";
    }

    static List<String> parseRanking(ObjectMapper mapper, String response, List<String> candidates) {
        if (response == null || response.isBlank()) return List.of();
        String cleaned = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        Set<String> valid = Set.copyOf(candidates);

        // Phase 1: try JSON array parse if brackets are present
        int start = cleaned.indexOf('[');
        int end   = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode arr = mapper.readTree(cleaned.substring(start, end + 1));
                if (arr.isArray()) {
                    List<String> result = new ArrayList<>();
                    for (JsonNode node : arr) {
                        String matched = matchCandidate(node.asText("").trim(), valid);
                        if (matched != null && !result.contains(matched)) result.add(matched);
                    }
                    if (!result.isEmpty()) return result;
                }
            } catch (Exception ignored) {}
        }

        // Phase 2: text scan — handles numbered lists, prose, unquoted arrays, no brackets
        // Sort by first occurrence position to preserve rank order.
        List<String> fallback = candidates.stream()
                .filter(c -> {
                    if (cleaned.contains(c)) return true;
                    String suffix = c.contains("/") ? c.substring(c.lastIndexOf('/') + 1) : null;
                    return suffix != null && cleaned.contains(suffix);
                })
                .sorted(java.util.Comparator.comparingInt(c -> {
                    int pos = cleaned.indexOf(c);
                    if (pos < 0) {
                        String suffix = c.substring(c.lastIndexOf('/') + 1);
                        pos = cleaned.indexOf(suffix);
                    }
                    return pos < 0 ? Integer.MAX_VALUE : pos;
                }))
                .collect(Collectors.toList());

        if (fallback.isEmpty()) {
            log.warn("GroqPool: could not extract any model names from ranking response: {}",
                    cleaned.substring(0, Math.min(cleaned.length(), 400)));
        }
        return fallback;
    }

    // Exact match first; then suffix match to handle models that drop their namespace prefix
    // e.g. "gpt-oss-120b" → "openai/gpt-oss-120b", "llama-3.3-70b-versatile" → exact
    static String matchCandidate(String name, Set<String> valid) {
        if (valid.contains(name)) return name;
        return valid.stream()
                .filter(c -> c.endsWith("/" + name))
                .findFirst()
                .orElse(null);
    }

    // --- Ollama quality scoring ---

    private Map<String, Integer> scoreWithOllama(Map<String, String> testResponses,
                                                  List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are evaluating AI model responses to a physics hypothesis-assessment task.\n\n");
        sb.append("Task given to each model:\n").append(TEST_PROMPT).append("\n\n");
        sb.append("Correct answer:\n");
        sb.append("- verdict: REFUTED (error first exceeds 1% at 25 degrees, not below 20)\n");
        sb.append("- critical_angle_deg: 25\n");
        sb.append("- reasoning should cite the 1.20% value at 25 degrees\n\n");
        sb.append("Rate each response 0-10 on three dimensions:\n");
        sb.append("- verdict_correct: 10=REFUTED chosen, 5=PARTIAL, 0=SUPPORTED or absent\n");
        sb.append("- angle_correct: 10=25, 7=within 2 degrees, 3=within 10 degrees, 0=wrong or absent\n");
        sb.append("- reasoning_quality: 10=cites specific data, 5=correct but vague, 0=wrong or absent\n\n");
        sb.append("Model responses:\n");
        for (Map.Entry<String, String> e : testResponses.entrySet()) {
            sb.append("[").append(e.getKey()).append("]: ")
              .append(e.getValue().replace("\n", " ")).append("\n");
        }
        sb.append("\nReply with ONLY this JSON using exact model IDs as keys, no other text:\n");
        sb.append("{\"model-id\":{\"verdict_correct\":N,\"angle_correct\":N,\"reasoning_quality\":N},...}");

        String response = callOllamaSync(sb.toString());
        return parseOllamaScores(response, candidates);
    }

    private Map<String, Integer> parseOllamaScores(String response, List<String> candidates) {
        if (response == null || response.isBlank()) return Map.of();
        String cleaned = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return Map.of();
        try {
            JsonNode root = mapper.readTree(cleaned.substring(start, end + 1));
            Map<String, Integer> result = new HashMap<>();
            for (String candidate : candidates) {
                JsonNode node = root.get(candidate);
                if (node == null || node.isMissingNode()) continue;
                int verdict   = node.path("verdict_correct").asInt(0);
                int angle     = node.path("angle_correct").asInt(0);
                int reasoning = node.path("reasoning_quality").asInt(0);
                result.put(candidate, verdict + angle + reasoning);
            }
            return result;
        } catch (Exception e) {
            log.debug("GroqPool: failed to parse Ollama scores: {}", e.getMessage());
            return Map.of();
        }
    }

    private String callOllamaSync(String prompt) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", ollamaJudgeModel);
            payload.put("stream", false);
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaJudgeUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Ollama judge error " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readTree(resp.body()).path("message").path("content").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GroqPool: Ollama judge call failed", e);
        }
    }

    // --- Model list fetch ---

    private List<String> fetchCandidateModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                log.error("================================================================================");
                log.error("GroqPool: Groq API key is invalid (401). Check GROQ_API_KEY. Exiting.");
                log.error("================================================================================");
                throw new IllegalStateException("GroqPool: invalid Groq API key — application cannot start");
            }
            if (resp.statusCode() != 200) {
                log.error("================================================================================");
                log.error("GroqPool: Groq API returned {} — service may be unavailable. Exiting.", resp.statusCode());
                log.error("================================================================================");
                throw new IllegalStateException("GroqPool: Groq returned " + resp.statusCode() + " — application cannot start");
            }
            JsonNode root = mapper.readTree(resp.body());
            List<String> primaryModels  = new ArrayList<>();
            List<String> fastTierModels = new ArrayList<>();
            List<String> excluded       = new ArrayList<>();
            for (JsonNode node : root.path("data")) {
                String id = node.path("id").asText("");
                if (id.isBlank()) continue;
                ModelTier tier = classifyTier(id);
                switch (tier) {
                    case PRIMARY  -> primaryModels.add(id);
                    case FAST     -> fastTierModels.add(id);
                    case EXCLUDED -> excluded.add(id);
                }
            }
            if (!excluded.isEmpty()) {
                log.info("GroqPool: excluded {} non-chat / no-size models: {}",
                        excluded.size(), excluded);
            }
            if (!fastTierModels.isEmpty()) {
                log.info("GroqPool: routed {} sub-{}B chat models to fast tier: {}",
                        fastTierModels.size(), MIN_MODEL_SIZE_B, fastTierModels);
            }
            // Stash for the constructor's fast-tier selection pass.
            pendingFastTierCandidates.addAll(fastTierModels);
            if (primaryModels.isEmpty()) {
                log.error("GroqPool: Groq returned no primary-tier (70B+) chat models. Exiting.");
                throw new IllegalStateException("GroqPool: no primary chat models — application cannot start");
            }
            return primaryModels;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("================================================================================");
            log.error("GroqPool: Groq API is unreachable — check network / GROQ_API_KEY. Exiting.");
            log.error("================================================================================");
            throw new RuntimeException("GroqPool: Groq unreachable — application cannot start", e);
        }
    }

    /**
     * Phase 9 U7 — three-way classification of a Groq model id:
     * <ul>
     *   <li>{@code PRIMARY} — chat model, ≥{@value #MIN_MODEL_SIZE_B}B parameters. Joins
     *       {@link #activeModels} and {@code PrimaryModelRegistry}.</li>
     *   <li>{@code FAST} — chat model, &lt;{@value #MIN_MODEL_SIZE_B}B. Joins
     *       {@link #fastTierActiveModels} and {@code FastModelRegistry}.</li>
     *   <li>{@code EXCLUDED} — non-chat (whisper/orpheus/guard) or no size in the
     *       id (groq/compound, etc.). Dropped entirely.</li>
     * </ul>
     */
    private enum ModelTier { PRIMARY, FAST, EXCLUDED }

    private ModelTier classifyTier(String id) {
        String lower = id.toLowerCase();
        if (EXCLUDED_SUBSTRINGS.stream().anyMatch(lower::contains)) return ModelTier.EXCLUDED;
        OptionalInt size = extractModelSizeB(lower);
        if (size.isEmpty()) return ModelTier.EXCLUDED;  // can't classify → drop
        return size.getAsInt() >= MIN_MODEL_SIZE_B ? ModelTier.PRIMARY : ModelTier.FAST;
    }

    // Returns the largest Nb value found in the model ID.
    // "8x7b" → 7 (per-token active params for MoE), "70b" → 70, "120b" → 120.
    private OptionalInt extractModelSizeB(String lower) {
        Matcher m = Pattern.compile("(\\d+)b").matcher(lower);
        int max = -1;
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v > max) max = v;
        }
        return max >= 0 ? OptionalInt.of(max) : OptionalInt.empty();
    }

    // --- HTTP calls ---

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
                    // TPD (daily quota) exhaustion — retry-after is hours/minutes, skip immediately
                    if (msg.contains("tokens per day") || msg.contains("TPD")) {
                        long waitMs = parseRetryAfterMs(msg).orElse(60 * 60 * 1000L);
                        long expiry = System.currentTimeMillis() + waitMs;
                        tpdExpiry.put(modelId, expiry);
                        log.warn("GroqPool: TPD quota exhausted model={} — blocked for {}ms until ~{}",
                                modelId, waitMs, java.time.Instant.ofEpochMilli(expiry));
                        throw e;
                    }
                    // TPM (per-minute) — use Groq's retry-after when available
                    long waitMs = parseRetryAfterMs(msg).orElse(backoffMs);
                    // Phase 9 follow-on (2026-05-14) — record per-model TPM expiry so
                    // concurrent threads see isModelBlocked()=true and skip this
                    // model immediately rather than racing past the check and firing
                    // their own 429s. Only set on the first attempt of this 429
                    // (already-set expiry from a sibling thread takes precedence).
                    long expiry = System.currentTimeMillis() + waitMs;
                    tpmExpiry.merge(modelId, expiry, Math::max);
                    log.warn("GroqPool: 429 model={} attempt={} retrying in {}ms (tpmExpiry until ~{})",
                            modelId, attempt + 1, waitMs,
                            java.time.Instant.ofEpochMilli(tpmExpiry.get(modelId)));
                    sleep(waitMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                } else {
                    throw e;
                }
            }
        }
        throw last;
    }

    // Groq embeds retry-after as "4.82s" or "43m51.312s" or "1h23m45s"
    OptionalLong parseRetryAfterMs(String message) {
        if (message == null) return OptionalLong.empty();
        // "Xm Y.Zs" form
        Matcher mm = Pattern.compile("(\\d+)m(\\d+\\.?\\d*)s").matcher(message);
        if (mm.find()) {
            try {
                long ms = Long.parseLong(mm.group(1)) * 60_000
                        + (long)(Double.parseDouble(mm.group(2)) * 1000) + 500;
                return OptionalLong.of(ms);
            } catch (NumberFormatException ignored) {}
        }
        // "try again in X.Ys" form
        Matcher ms = Pattern.compile("try again in (\\d+\\.?\\d*)s").matcher(message);
        if (ms.find()) {
            try {
                return OptionalLong.of((long)(Double.parseDouble(ms.group(1)) * 1000) + 500);
            } catch (NumberFormatException ignored) {}
        }
        // Phase 9 follow-on — bare "X.Ys" anywhere in the message (Groq sometimes
        // omits the "try again in" preamble; some upstream errors include just
        // "X.Ys remaining" or similar).
        Matcher bare = Pattern.compile("\\b(\\d+\\.?\\d*)\\s*s\\b").matcher(message);
        if (bare.find()) {
            try {
                double secs = Double.parseDouble(bare.group(1));
                if (secs > 0 && secs < 3600) {  // sanity bound: skip "5G" style noise
                    return OptionalLong.of((long)(secs * 1000) + 500);
                }
            } catch (NumberFormatException ignored) {}
        }
        return OptionalLong.empty();
    }

    private String extractRetryAfterLabel(String message) {
        Matcher m = Pattern.compile("try again in ([^.\"]+)").matcher(message);
        return m.find() ? m.group(1).trim() : "unknown wait";
    }

    private String syncCall(String modelId, String prompt, int tokenLimit) {
        // Phase 9 G9 — pre-debit reservation. Throws BudgetExceededException
        // BEFORE any HTTP request when the estimate would push the per-minute
        // budget past the pause threshold. When the gate isn't wired (tests),
        // we fall back to the legacy post-spend recordUsage path below.
        long estimate = estimateTokens(prompt, tokenLimit);
        boolean reserved = false;
        if (budgetGate != null) {
            budgetGate.tryAcquireOrThrow(PROVIDER_ID, modelId, estimate);
            reserved = true;
        }
        String outcome = "ok";
        String payload = buildPayload(modelId, prompt, false, tokenLimit);
        long t0 = System.currentTimeMillis();
        int respStatus = 0;
        String respBody = "";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            respStatus = resp.statusCode();
            respBody = resp.body();
            if (resp.statusCode() != 200) {
                outcome = "http_error";
                throw new RuntimeException("Groq API error " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root    = mapper.readTree(resp.body());
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                // Reasoning models (e.g. openai/gpt-oss-120b) put chain-of-thought in "reasoning"
                // and may leave "content" empty when token budget is exhausted mid-reasoning.
                content = message.path("reasoning").asText("");
            }
            JsonNode usage = root.path("usage");
            if (reserved) {
                // Replace the pre-recorded estimate with actual usage via correct().
                budgetGate.correct(PROVIDER_ID, modelId, estimate, totalFromUsage(usage));
            } else {
                recordUsage(modelId, usage);
            }
            return content;
        } catch (RuntimeException e) {
            if ("ok".equals(outcome)) outcome = "io_error";
            throw e;
        } catch (Exception e) {
            outcome = "io_error";
            throw new RuntimeException("GroqPool sync call failed", e);
        } finally {
            recordCall(modelId, "sync", outcome);
            if (traceWriter != null) {
                traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                        PROVIDER_ID, modelId, "sync", respStatus,
                        System.currentTimeMillis() - t0, payload, respBody);
            }
        }
    }

    private void recordCall(String modelId, String mode, String outcome) {
        if (callMetrics != null) callMetrics.record(PROVIDER_ID, modelId, mode, outcome);
    }

    private void recordUsage(String model, JsonNode usage) {
        if (tokenAccountant == null || usage == null || usage.isMissingNode() || usage.isNull()) return;
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt + completion <= 0) return;
        tokenAccountant.record(new TokenUsage(
                PROVIDER_ID, model, prompt, completion, java.time.Instant.now()));
    }

    /**
     * Phase 9 G9 — conservative token estimate for pre-debit reservation.
     * Rough heuristic: ~3 chars per token (more conservative than the
     * standard 4 chars/token because Groq's mix of English + code + math is
     * tokenized denser than plain English). Total estimate = input + max
     * output; we'd rather pause early than overshoot the budget by 72%.
     */
    private long estimateTokens(String prompt, int maxOutput) {
        long inputEstimate = (prompt == null) ? 0L : Math.max(1L, prompt.length() / 3L);
        return inputEstimate + Math.max(0, maxOutput);
    }

    /** Phase 9 G9 — extract prompt+completion totals from a {@code usage} JSON node. */
    private long totalFromUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return 0L;
        return usage.path("prompt_tokens").asLong(0)
                + usage.path("completion_tokens").asLong(0);
    }

    private void streamCall(String modelId, String prompt, Consumer<String> tokenConsumer) {
        // Phase 9 G9 — pre-debit reservation, same shape as syncCall. Stream
        // responses emit the final `usage` JSON in a terminating SSE chunk;
        // we correct against that when it arrives.
        final long estimate = estimateTokens(prompt, maxTokens);
        final boolean reserved;
        if (budgetGate != null) {
            budgetGate.tryAcquireOrThrow(PROVIDER_ID, modelId, estimate);
            reserved = true;
        } else {
            reserved = false;
        }
        String outcome = "ok";
        String payload = buildPayload(modelId, prompt, true, maxTokens);
        long t0 = System.currentTimeMillis();
        StringBuilder accum = new StringBuilder();
        int respStatus = 0;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<java.util.stream.Stream<String>> sresp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            respStatus = sresp.statusCode();
            sresp.body()
                    .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                    .forEach(line -> {
                        try {
                            JsonNode chunk = mapper.readTree(line.substring(6));
                            // With stream_options.include_usage=true, the final chunk has
                            // an empty choices[] and a populated `usage` field.
                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                if (reserved) {
                                    budgetGate.correct(PROVIDER_ID, modelId, estimate, totalFromUsage(usage));
                                } else {
                                    recordUsage(modelId, usage);
                                }
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
            throw new RuntimeException("GroqPool stream call failed", e);
        } finally {
            recordCall(modelId, "stream", outcome);
            if (traceWriter != null) {
                traceWriter.record(com.emergentterrain.llm.LlmCallContext.currentPaperId(),
                        PROVIDER_ID, modelId, "stream", respStatus,
                        System.currentTimeMillis() - t0, payload, accum.toString());
            }
        }
    }

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
            if (stream) {
                // Groq emits a final SSE chunk with usage.{prompt,completion}_tokens
                // when stream_options.include_usage=true. Required for budget tracking.
                node.putObject("stream_options").put("include_usage", true);
            }
            ArrayNode messages = node.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("GroqPool: failed to build payload", e);
        }
    }

    private void acquireSlot() {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for GroqPool slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
