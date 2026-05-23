package com.emergentterrain.llm;

/**
 * Thrown by {@link TpmBudgetGate#tryAcquireOrThrow} when a process-wide LLM cap
 * ({@code app.ai.call-limit.max-calls} or {@code .max-tokens}) has tripped.
 *
 * <p>By the time this is thrown the application is already terminating (see
 * {@link SessionCallLimit}); the throw exists only to stop the over-limit call
 * from reaching the network during the shutdown window. Like
 * {@link BudgetExceededException} it is unchecked, so existing call-site
 * fall-through handling treats it uniformly.
 */
public class CallLimitExceededException extends RuntimeException {

    public CallLimitExceededException(String reason) {
        super("LLM call blocked — session cap tripped: " + reason + "; application terminating");
    }
}
