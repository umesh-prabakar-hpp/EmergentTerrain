package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Phase 9 U2 — model-centric routing surface that aggregates every model
 * across every pool of a given tier. Replaces per-provider pool walking with
 * a single sequential walk over {@link ModelHandle}s.
 *
 * <p>The registry is the substrate {@link LlmRouter} and
 * {@code ReviewPanelService} route through in Phase 9 U3. Two concrete
 * registries land in this phase:
 * <ul>
 *   <li>{@code PrimaryModelRegistry} — aggregates every 70B+ tools-capable model
 *       (Groq, NVIDIA, OpenRouter, Cerebras).</li>
 *   <li>{@code FastModelRegistry} (U6) — aggregates sub-70B / fast-tier
 *       models (Ollama today).</li>
 * </ul>
 *
 * <p>{@link #availableModels()} drives every call: it filters the full handle
 * list down to those whose owning pool reports the model is not currently
 * blocked, in registration order. The {@code callSync} / {@code callStream} /
 * {@code callWithTools} entry points walk that list, dispatching each handle
 * to its pool's per-model entry point (added in U1), and return the first
 * success. Sequential by construction — operator direction 2026-05-14.
 *
 * <p>The registry intentionally does <b>not</b> own per-model block state.
 * That continues to live on the pool (so 429 + retry-after-header parsing
 * stays in one place). The registry is a routing layer, not a cache.
 */
public interface ModelRegistry {

    /** All handles in the registry, regardless of current block status. */
    List<ModelHandle> allModels();

    /**
     * Handles whose model is currently not blocked. Registration order is
     * preserved — pure availability-based picking, per operator direction
     * 2026-05-15 (no provider-diversity bonus, no health-weighted reorder).
     */
    List<ModelHandle> availableModels();

    /** Convenience — {@code allModels().size()}. */
    default int totalModelCount() { return allModels().size(); }

    /**
     * Synchronous call. Walks {@link #availableModels()} sequentially,
     * dispatching each handle to {@link LlmPool#callSyncOnModel(String, String)}.
     * Returns the first non-throwing result.
     *
     * @throws RuntimeException if every handle is blocked or every dispatch
     *         throws — caller should fall back (e.g. to a different tier) or
     *         surface the error.
     */
    String callSync(String prompt);

    /**
     * Streaming call. Same selection contract as {@link #callSync(String)},
     * but dispatched through {@link LlmPool#callStreamOnModel(String, String,
     * java.util.function.Consumer)}.
     */
    void callStream(String prompt, Consumer<String> tokenConsumer);

    /**
     * Tool-enabled call. Same selection contract as the others; dispatched
     * through {@link LlmPool#callWithToolsOnModel(String, String, String,
     * String, java.util.function.Function)}. The returned
     * {@link ToolCallResponse} carries both the assistant synthesis and the
     * full tool-call trace (Phase 7 F5).
     */
    ToolCallResponse callWithTools(String systemPrompt, String userPrompt,
                                    String toolsJson,
                                    Function<String, String> executor);
}
