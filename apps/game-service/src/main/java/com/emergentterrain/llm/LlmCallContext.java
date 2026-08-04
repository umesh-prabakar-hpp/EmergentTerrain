package com.emergentterrain.llm;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Phase 17 — per-thread sampling-temperature override for LLM calls.
 *
 * <p>Background: every chat-completions pool ({@code GroqPool},
 * {@code NvidiaPool}, {@code OpenRouterPool}, {@code CerebrasPool},
 * {@code OllamaPool}) hard-codes {@code temperature: 0.7} in its request
 * body. That's a reasonable default for prose synthesis but is wrong for:
 * <ul>
 *   <li>Citation insertion / numeric-claim extraction (we want the model
 *       to deterministically point at the right {@code [call_N]} or
 *       echo a number bit-for-bit) — {@code 0.0} is right.</li>
 *   <li>JSON-schema-constrained revisions (no creativity needed; the
 *       schema is the contract) — {@code 0.0}.</li>
 *   <li>Panel-revision and student revision (a focused fix targeting the
 *       panel's named concerns) — {@code 0.0}–{@code 0.2}.</li>
 * </ul>
 *
 * <p>Plumbing a {@code double temperature} through every pool method
 * signature would touch all six pool implementations. Instead, we expose
 * a thread-local override: callers set it for the scope of the call,
 * pools read it and fall back to their hard-coded default when unset.
 *
 * <p>Usage:
 * <pre>{@code
 *   LlmCallContext.withTemperature(0.0, () -> {
 *       router.streamPrimary(prompt, sink);
 *   });
 *   // ↑ the temperature override is automatically cleared after the body
 * }</pre>
 *
 * <p>Pools consult {@link #currentTemperature()} just before serialising
 * the request body and use the override when present, the default
 * otherwise.
 *
 * <p>Thread safety: the ThreadLocal is per-thread by definition. Each
 * narration runs on its own virtual thread, so cross-narration leakage
 * is impossible. The {@code try/finally} in the helper guarantees the
 * override clears even on exception — losing a thread-local across
 * calls in production is the bug class we're explicitly preventing.
 */
public final class LlmCallContext {

    private LlmCallContext() {}

    private static final ThreadLocal<Double>  TEMPERATURE      = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> JSON_OUTPUT_MODE = new ThreadLocal<>();
    /**
     * Lever 1 — optional JSON schema (a serialized JSON object string) the
     * next LLM call should constrain its output to via
     * {@code response_format: {"type":"json_schema","json_schema":{...,
     * "strict":true}}}. Stronger than {@link #JSON_OUTPUT_MODE}: the provider
     * enforces the field shape, not just "valid JSON". Pools emit it only when
     * the schema is set AND {@code ProviderConfig.supportsJsonSchema()} is true,
     * otherwise they fall back to the {@code json_object} branch. Null when
     * unset (today's behaviour).
     */
    private static final ThreadLocal<String>  JSON_SCHEMA      = new ThreadLocal<>();
    private static final ThreadLocal<String>  PAPER_ID         = new ThreadLocal<>();
    /**
     * Phase 20.4 step 2b-2 — per-paper-scope cumulative token totals.
     * Reset to {@code {0, 0}} on every {@link #withPaperId(String, Runnable)}
     * entry; accumulated as pools call {@link #recordTokenUsage(long, long)}
     * from their {@code recordUsage} method. Read by the narration envelope
     * stamp at the end of the paper's orchestration.
     *
     * Layout: {@code long[2]} where index 0 = input (prompt) tokens, index 1
     * = output (completion) tokens. Array rather than two separate fields so
     * a single allocation backs both counters; pool callers mutate in place.
     * Null when no {@code withPaperId} scope is active — pools no-op.
     */
    private static final ThreadLocal<long[]>  TOKEN_TOTALS     = new ThreadLocal<>();
    /**
     * Phase 20.5 — per-workload role tag consulted by
     * {@code RoleRouterPolicy} to filter / reorder the primary registry's
     * handle list for the current call. Empty / null = no role; the
     * registry's global ordering wins. Set by call sites that want
     * operator-tunable per-role routing (fast-audit, professor-tools,
     * panel-review, etc.) via {@link #withRole}.
     */
    private static final ThreadLocal<String>  CURRENT_ROLE     = new ThreadLocal<>();

    /**
     * Returns the temperature override for the current thread, or
     * {@link OptionalDouble#empty()} when no caller set one. Pools should
     * read this just before building the request body and apply
     * {@code .orElse(<their-default>)}.
     */
    public static OptionalDouble currentTemperature() {
        Double v = TEMPERATURE.get();
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /**
     * Run {@code body} with the given temperature override visible to any
     * LLM call this thread makes within the scope. Temperature is cleared
     * on normal completion AND on exception, so the override never leaks
     * to subsequent unrelated calls.
     *
     * @param temperature sampling temperature in {@code [0.0, 2.0]} —
     *                    not clamped here; pools may clamp per their
     *                    provider's accepted range
     * @param body        the call(s) to run with the override active
     */
    public static void withTemperature(double temperature, Runnable body) {
        Double prior = TEMPERATURE.get();
        TEMPERATURE.set(temperature);
        try {
            body.run();
        } finally {
            if (prior == null) TEMPERATURE.remove();
            else TEMPERATURE.set(prior);
        }
    }

    /**
     * Supplier overload — same scoping rules, returns whatever the body
     * returns. Useful for sync {@code callPrimary} / {@code callFast}
     * calls that need a value back.
     */
    public static <T> T withTemperature(double temperature, Supplier<T> body) {
        Double prior = TEMPERATURE.get();
        TEMPERATURE.set(temperature);
        try {
            return body.get();
        } finally {
            if (prior == null) TEMPERATURE.remove();
            else TEMPERATURE.set(prior);
        }
    }

    /**
     * Phase 17 (analyses §2.3) — should the next LLM call constrain its
     * output to valid JSON via {@code response_format: {"type":
     * "json_object"}}? Pools that support OpenAI-compatible
     * response_format read this flag just before serialising and add the
     * directive when true. The provider then guarantees the response
     * starts with {@code {} and ends with {@code }} — no markdown code
     * fences, no prose preamble, no trailing comments.
     *
     * <p>Closes the {@code rev:parse_failed→fp} failure mode named in
     * REVIEW_REPORT.md refresh #1 §1 rec #3 (9 of 23 published papers in
     * one refresh — ~39%). Pair with {@link #withTemperature} at 0.0 on
     * revision-class calls.
     *
     * <p>Returns {@code false} when unset (today's behaviour).
     */
    public static boolean isJsonOutputMode() {
        Boolean v = JSON_OUTPUT_MODE.get();
        return v != null && v;
    }

    /**
     * Run {@code body} with the JSON-output-mode flag set on this thread.
     * Auto-clears on body completion AND on exception — same finally
     * discipline as {@link #withTemperature}.
     */
    public static void withJsonOutputMode(Runnable body) {
        Boolean prior = JSON_OUTPUT_MODE.get();
        JSON_OUTPUT_MODE.set(Boolean.TRUE);
        try {
            body.run();
        } finally {
            if (prior == null) JSON_OUTPUT_MODE.remove();
            else JSON_OUTPUT_MODE.set(prior);
        }
    }

    /** Supplier overload — see {@link #withJsonOutputMode(Runnable)}. */
    public static <T> T withJsonOutputMode(Supplier<T> body) {
        Boolean prior = JSON_OUTPUT_MODE.get();
        JSON_OUTPUT_MODE.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (prior == null) JSON_OUTPUT_MODE.remove();
            else JSON_OUTPUT_MODE.set(prior);
        }
    }

    /**
     * Lever 1 — the JSON schema (a serialized JSON object string) the next
     * LLM call should be constrained to, or {@link Optional#empty()} when no
     * caller set one. Pools read this just before serialising the request and
     * emit {@code response_format: json_schema} only when present AND the
     * provider's {@code supportsJsonSchema()} is true; otherwise they fall
     * back to {@link #isJsonOutputMode()}.
     */
    public static Optional<String> currentJsonSchema() {
        return Optional.ofNullable(JSON_SCHEMA.get());
    }

    /**
     * Run {@code body} with {@code schema} set as the structured-output
     * contract for any LLM call this thread makes within the scope. Auto-clears
     * on completion AND on exception — same finally discipline as
     * {@link #withJsonOutputMode}. Typically paired with
     * {@link #withTemperature}(0.0) on revision-class calls.
     */
    public static void withJsonSchema(String schema, Runnable body) {
        String prior = JSON_SCHEMA.get();
        JSON_SCHEMA.set(schema);
        try {
            body.run();
        } finally {
            if (prior == null) JSON_SCHEMA.remove();
            else JSON_SCHEMA.set(prior);
        }
    }

    /** Supplier overload — see {@link #withJsonSchema(String, Runnable)}. */
    public static <T> T withJsonSchema(String schema, Supplier<T> body) {
        String prior = JSON_SCHEMA.get();
        JSON_SCHEMA.set(schema);
        try {
            return body.get();
        } finally {
            if (prior == null) JSON_SCHEMA.remove();
            else JSON_SCHEMA.set(prior);
        }
    }

    /**
     * Current paper ID scope for any LLM call this thread makes, or
     * {@code null} when no caller set one. Read by {@code LlmTraceWriter}
     * at the HTTP-send site so per-call traces can be written to
     * {@code research/llm-traces/{paperId}.jsonl}.
     */
    public static String currentPaperId() {
        return PAPER_ID.get();
    }

    /**
     * Run {@code body} with the given paper ID visible to any LLM call
     * this thread makes within the scope. PaperId is cleared on normal
     * completion AND on exception, so it never leaks to subsequent
     * unrelated calls. Same finally discipline as {@link #withTemperature}.
     */
    public static void withPaperId(String paperId, Runnable body) {
        String prior = PAPER_ID.get();
        long[] priorTokens = TOKEN_TOTALS.get();
        PAPER_ID.set(paperId);
        TOKEN_TOTALS.set(new long[2]);
        try {
            body.run();
        } finally {
            if (prior == null) PAPER_ID.remove();
            else PAPER_ID.set(prior);
            if (priorTokens == null) TOKEN_TOTALS.remove();
            else TOKEN_TOTALS.set(priorTokens);
        }
    }

    /** Supplier overload — see {@link #withPaperId(String, Runnable)}. */
    public static <T> T withPaperId(String paperId, Supplier<T> body) {
        String prior = PAPER_ID.get();
        long[] priorTokens = TOKEN_TOTALS.get();
        PAPER_ID.set(paperId);
        TOKEN_TOTALS.set(new long[2]);
        try {
            return body.get();
        } finally {
            if (prior == null) PAPER_ID.remove();
            else PAPER_ID.set(prior);
            if (priorTokens == null) TOKEN_TOTALS.remove();
            else TOKEN_TOTALS.set(priorTokens);
        }
    }

    /**
     * Phase 20.4 step 2b-2 — pool-side hook. Accumulate prompt+completion
     * tokens for the active paper-scope, if any. No-op outside a
     * {@link #withPaperId} scope (the typical non-narration paths — model
     * selection probes, panel reviews driven from elsewhere, retest
     * scheduling). Same-thread safety only; virtual-thread-per-narration
     * means there's no cross-narration contention.
     *
     * <p>Pools call this from their {@code recordUsage} method alongside
     * the existing {@code TokenAccountant.record} (which writes
     * provider/model-scoped totals). Together they let us answer both
     * "how many tokens did THIS paper cost" (this counter) and "how many
     * tokens did THIS model burn today" (the accountant) without one
     * being derivable from the other.
     */
    public static void recordTokenUsage(long promptTokens, long completionTokens) {
        long[] totals = TOKEN_TOTALS.get();
        if (totals == null) return;
        totals[0] += promptTokens;
        totals[1] += completionTokens;
    }

    /**
     * Phase 20.4 step 2b-2 — read the cumulative input (prompt) tokens
     * spent under the current paper-scope. Returns 0 outside a
     * {@link #withPaperId} scope or when no pool has yet recorded usage.
     */
    public static long currentInputTokens() {
        long[] totals = TOKEN_TOTALS.get();
        return totals == null ? 0L : totals[0];
    }

    /** Phase 20.4 step 2b-2 — output (completion) tokens, same scope. */
    public static long currentOutputTokens() {
        long[] totals = TOKEN_TOTALS.get();
        return totals == null ? 0L : totals[1];
    }

    /**
     * Phase 20.5 — current role tag for the active LLM call scope, or
     * {@code null} when no caller set one. Read by {@code RoleRouterPolicy}
     * to look up per-role overrides in {@code app.ai.role-overrides:}.
     * Returning null means "no override; use the global ordering."
     */
    public static String currentRole() {
        return CURRENT_ROLE.get();
    }

    /**
     * Phase 20.5 — run {@code body} with {@code role} tagged on the current
     * thread so any LLM call this thread makes within the scope can be
     * filtered / reordered by {@code RoleRouterPolicy}. Role is cleared on
     * normal completion AND on exception (same finally discipline as
     * {@link #withPaperId}) so it never leaks to subsequent unrelated calls.
     *
     * <p>Typical role names: {@code "fast-audit"}, {@code "professor-tools"},
     * {@code "panel-review"}, {@code "narration"}. The set is open — any
     * string the operator writes in {@code app.ai.role-overrides:} matches.
     */
    public static void withRole(String role, Runnable body) {
        String prior = CURRENT_ROLE.get();
        CURRENT_ROLE.set(role);
        try {
            body.run();
        } finally {
            if (prior == null) CURRENT_ROLE.remove();
            else CURRENT_ROLE.set(prior);
        }
    }

    /** Supplier overload — see {@link #withRole(String, Runnable)}. */
    public static <T> T withRole(String role, Supplier<T> body) {
        String prior = CURRENT_ROLE.get();
        CURRENT_ROLE.set(role);
        try {
            return body.get();
        } finally {
            if (prior == null) CURRENT_ROLE.remove();
            else CURRENT_ROLE.set(prior);
        }
    }

    /**
     * Clear the override unconditionally. Tests use this to guarantee a
     * clean slate; production code should prefer the
     * {@link #withTemperature} / {@link #withJsonOutputMode} helpers
     * since they auto-clear via finally.
     */
    public static void clear() {
        TEMPERATURE.remove();
        JSON_OUTPUT_MODE.remove();
        JSON_SCHEMA.remove();
        PAPER_ID.remove();
        TOKEN_TOTALS.remove();
        CURRENT_ROLE.remove();
    }
}
