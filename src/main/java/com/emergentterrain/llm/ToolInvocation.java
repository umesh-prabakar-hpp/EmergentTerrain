package com.emergentterrain.llm;

/**
 * Phase 7 F5 — one tool-call the LLM issued during an agentic tool-loop turn.
 *
 * Captures the function name the LLM chose, the JSON arguments it passed, the
 * JSON result the executor returned, and which round of the tool-loop produced
 * the call. Persisted as Tier 2 of F5's two-tier provenance scheme: every
 * invocation survives in the audit trail even if its result was not cited in
 * the final synthesis.
 *
 * <p>{@code argsJson} and {@code resultJson} are kept as raw JSON strings — the
 * trace is downstream of the LLM's free-form JSON, so we don't bind a Java
 * shape to it. F5.5's numerical-consistency check parses the result-side
 * structurally; the renderer (F5.4) treats both fields as opaque text for the
 * Methods appendix.
 *
 * <p>Phase 11 C1 — adds {@code callId} (stable, monotonic, harness-assigned)
 * so the LLM can cite tool results by ID in its synthesis prose. Format is
 * {@code "call_N"} where N is the 1-based position in the trace. Pre-Phase-11
 * deserialization is preserved by a backward-compat constructor that defaults
 * {@code callId} to {@code null}; the citation validator treats null IDs as
 * pre-Phase-11 traces and exits early in warn mode.
 */
public record ToolInvocation(
        String functionName,
        String argsJson,
        String resultJson,
        int round,
        String callId) {

    public ToolInvocation(String functionName, String argsJson, String resultJson, int round) {
        this(functionName, argsJson, resultJson, round, null);
    }
}
