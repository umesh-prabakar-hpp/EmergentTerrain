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
 */
public record ToolCallResponse(
        String synthesis,
        List<ToolInvocation> trace) {

    public static ToolCallResponse ofSynthesis(String synthesis) {
        return new ToolCallResponse(synthesis, List.of());
    }
}
