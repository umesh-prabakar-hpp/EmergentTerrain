package com.emergentterrain.llm;

/**
 * Thrown by {@link TpmBudgetGate#tryAcquireOrThrow} when a pending LLM call's
 * estimated token cost would push the per-minute budget past
 * {@code pauseThresholdPct}. Distinct from a 429 rate-limit error: this fires
 * BEFORE any HTTP request is issued, so it's a soft local fail-fast that the
 * caller can convert to a {@code gate:closed:pre-debit} call-path marker or
 * a retry-after-cooldown.
 */
public class BudgetExceededException extends RuntimeException {

    private final String provider;
    private final long estimate;

    public BudgetExceededException(String provider, long estimate, long currentTotal, long pauseAt) {
        super(String.format(
                "pre-debit budget exceeded: provider=%s estimate=%d would push current=%d past pauseAt=%d",
                provider, estimate, currentTotal, pauseAt));
        this.provider = provider;
        this.estimate = estimate;
    }

    public String provider() { return provider; }
    public long   estimate() { return estimate; }
}
