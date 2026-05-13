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
     */
    default String callWithTools(String systemPrompt, String userPrompt,
                                  String toolsJson,
                                  Function<String, String> executor) {
        throw new UnsupportedOperationException(
                providerKey() + " pool does not support tool-calling");
    }

    /** True when every model in this pool has an active rate-limit / quota block. */
    default boolean allBlocked() { return false; }

    /** Earliest time any model in this pool recovers from a block (or "now" if none blocked). */
    default Instant earliestRecovery() { return Instant.now(); }
}
