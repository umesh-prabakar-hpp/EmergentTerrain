package com.emergentterrain.service;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public interface LlmPool {
    String callSync(String prompt);
    void callStream(String prompt, Consumer<String> tokenConsumer);
    String selectedModel();
    default List<String> selectedModels() { return List.of(selectedModel()); }
    int backendCount();
    int healthyCount();

    // Phase 7 E1.0 — provider-pluralised surface. Defaults keep current OllamaPool
    // (no tool-calling, no quota tracking) working without modification.

    /** Stable provider key for telemetry + per-provider routing. e.g. "groq", "ollama". */
    default String providerKey() {
        return getClass().getSimpleName().toLowerCase().replace("pool", "");
    }

    /** True when this pool supports multi-turn tool-calling (OpenAI-style tools[] + tool_calls). */
    default boolean supportsTools() { return false; }

    /**
     * Tool-enabled call. The professor LLM may invoke any of the supplied tools to gather
     * evidence, then writes a synthesis. The executor is called with the tool's JSON args
     * (after parsing by the implementation) and returns the tool's result as a string.
     *
     * <p>Phase 7 F5.2 — returns a {@link com.emergentterrain.llm.ToolCallResponse} so
     * the agentic loop's tool-call trace flows back to the caller (Tier 2 provenance).
     * Callers that only need the final assistant text read {@code .synthesis()}.
     */
    default com.emergentterrain.llm.ToolCallResponse callWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson,
            Function<String, String> executor) {
        throw new UnsupportedOperationException(
                providerKey() + " pool does not support tool-calling");
    }

    /** True when every model in this pool has an active rate-limit / quota block. */
    default boolean allBlocked() { return false; }

    /** Earliest time any model in this pool recovers from a block (or "now" if none blocked). */
    default Instant earliestRecovery() { return Instant.now(); }

    /**
     * Optional role tag — lets the router direct specific narration stages at specific
     * providers. Empty string means "general purpose; participate in primaryChain only".
     *
     * <p>Phase 7 E1.6 — defined roles:
     * <ul>
     *   <li>{@code "fast-audit"} — Cerebras's WSE-3 inference (~14 ms per call). Used by
     *       {@code ResearchNarrationService.runQualityAudit} to cut professor-stage
     *       latency. When the role-tagged pool is blocked, the router transparently
     *       falls back to the configured fast pool (Ollama by default).</li>
     * </ul>
     */
    default String role() { return ""; }

    // ── Phase 9 U1 — per-model entry points ───────────────────────────────
    //
    // These let the unified ModelRegistry target a specific model owned by
    // this pool. Default implementations delegate to the model-less surface
    // (callSync / callStream / callWithTools) for pools that only carry one
    // model — single-model pools get the new contract for free.
    //
    // Pools with multiple models (Groq, NVIDIA, OpenRouter, future Ollama
    // multi-model) override these to dispatch to the named model instead of
    // letting their internal round-robin pick.

    /**
     * List of model IDs currently active in this pool. The registry uses this
     * to enumerate {@code (pool, modelId)} tuples at construction. Default
     * implementation returns {@link #selectedModels()} which falls back to a
     * single-element list of {@link #selectedModel()}.
     */
    default List<String> modelIds() { return selectedModels(); }

    /**
     * Phase 9 U7 — model IDs in this pool that should be routed to the
     * <b>fast tier</b> (sub-70B / cheap / cost-bounded models — currently
     * Groq's gpt-oss-20b, llama-3.1-8b-instant, qwen3-32b, etc.). Default
     * empty list; tool-capable pools that classify sub-70B chat models into
     * the fast tier override this to expose them to
     * {@code FastModelRegistry}. These models stay invisible to
     * {@code PrimaryModelRegistry} so panel and primary-tier callers never
     * see them as candidates.
     */
    default List<String> fastTierModelIds() { return List.of(); }

    /**
     * Phase 9 follow-on (2026-05-14) — models that failed initial selection
     * and should be re-probed periodically by {@code ModelRetestService}.
     * Each entry carries the per-model dispatch lambdas the tester needs to
     * re-run chat + JSON + (where applicable) tool probes. Default empty —
     * pools without selection-test failures (or pools that don't run tests
     * at all) return nothing.
     */
    default List<RetestableModel> pendingRetests() { return List.of(); }

    /**
     * Phase 9 follow-on — called by {@code ModelRetestService} when a
     * previously-dropped model's re-probe passes. The pool should add
     * {@code modelId} back to its active set so the registries pick it up
     * on their next aggregation pass. Default no-op.
     */
    default void onModelRecovered(String modelId) {}

    /**
     * Phase 9 follow-on — bundle for a model awaiting periodic re-test.
     * {@code toolDispatch} is null for non-tool-tier candidates (Ollama, fast-tier).
     */
    record RetestableModel(
            String modelId,
            java.util.function.BiFunction<String, String, String> syncDispatch,
            ModelSelectionTester.ToolDispatch toolDispatch) {}

    /**
     * True when this specific model has an active rate-limit / quota block.
     * Default implementation maps the question onto the pool-wide
     * {@link #allBlocked()} for pools that don't track per-model state.
     */
    default boolean isModelBlocked(String modelId) { return allBlocked(); }

    /**
     * Sync call targeting a specific model. Pools that own multiple models
     * override to dispatch directly; single-model pools use the default which
     * just forwards to {@link #callSync(String)}.
     */
    default String callSyncOnModel(String modelId, String prompt) {
        return callSync(prompt);
    }

    /**
     * Streaming call targeting a specific model. Same delegation rule as
     * {@link #callSyncOnModel(String, String)}.
     */
    default void callStreamOnModel(String modelId, String prompt,
                                    Consumer<String> tokenConsumer) {
        callStream(prompt, tokenConsumer);
    }

    /**
     * Tool-call targeting a specific model. Pools without tool support throw
     * {@link UnsupportedOperationException}; pools with one tool-capable model
     * forward to {@link #callWithTools(String, String, String, Function)};
     * multi-model tool-capable pools override to dispatch on {@code modelId}.
     */
    default com.emergentterrain.llm.ToolCallResponse callWithToolsOnModel(
            String modelId, String systemPrompt, String userPrompt,
            String toolsJson, Function<String, String> executor) {
        return callWithTools(systemPrompt, userPrompt, toolsJson, executor);
    }
}
