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

    private static final ThreadLocal<Double> TEMPERATURE = new ThreadLocal<>();

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
     * Clear the override unconditionally. Tests use this to guarantee a
     * clean slate; production code should prefer the
     * {@link #withTemperature} helpers since they auto-clear via finally.
     */
    public static void clear() {
        TEMPERATURE.remove();
    }
}
