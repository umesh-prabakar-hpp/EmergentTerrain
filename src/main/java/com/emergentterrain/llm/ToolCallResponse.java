package com.emergentterrain.llm;

import java.util.List;

/**
 * Phase 7 F5.2 — return shape for {@code LlmPool.callWithTools}.
 *
 * <p>{@code synthesis} is the assistant's final free-form text after the
 * agentic tool-loop has closed (same string the old {@code String}-returning
 * contract produced). {@code trace} is the ordered list of every tool call
 * the LLM issued during the loop — Tier 2 of F5's two-tier provenance scheme.
 *
 * <p>For non-tool-using consumers (or the LlmRouter's plain-primary fallback
 * when no tool-capable pool is available), {@code trace} is an empty list.
 *
 * <p>Phase 12 D7 — {@code skippedModels} carries the IDs of the model handles
 * the router/pool tried and rejected (budget gate, TPD-recovery, tool-call
 * failure) before the one whose synthesis is in {@code synthesis} succeeded.
 * The narration service prepends {@code prof:budget_fallback:from=<modelId>}
 * stamps to the call path for each entry so reviewers can see when a paper
 * was salvaged after one or more model rejections. Empty list when the
 * first eligible model succeeded on its first attempt.
 */
public record ToolCallResponse(
        String synthesis,
        List<ToolInvocation> trace,
        List<String> skippedModels) {

    /** Two-arg overload — bit-identical to the pre-D7 shape; skippedModels defaults to empty. */
    public ToolCallResponse(String synthesis, List<ToolInvocation> trace) {
        this(synthesis, trace, List.of());
    }

    public static ToolCallResponse ofSynthesis(String synthesis) {
        return new ToolCallResponse(synthesis, List.of(), List.of());
    }
}
