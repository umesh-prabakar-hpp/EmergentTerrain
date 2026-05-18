package com.emergentterrain.llm;

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
    private static final ThreadLocal<String>  PAPER_ID         = new ThreadLocal<>();

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
        PAPER_ID.set(paperId);
        try {
            body.run();
        } finally {
            if (prior == null) PAPER_ID.remove();
            else PAPER_ID.set(prior);
        }
    }

    /** Supplier overload — see {@link #withPaperId(String, Runnable)}. */
    public static <T> T withPaperId(String paperId, Supplier<T> body) {
        String prior = PAPER_ID.get();
        PAPER_ID.set(paperId);
        try {
            return body.get();
        } finally {
            if (prior == null) PAPER_ID.remove();
            else PAPER_ID.set(prior);
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
        PAPER_ID.remove();
    }
}
