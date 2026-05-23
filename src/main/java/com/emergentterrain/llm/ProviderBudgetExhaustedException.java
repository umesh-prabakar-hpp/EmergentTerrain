package com.emergentterrain.llm;

/**
 * Thrown by {@link TpmBudgetGate#tryAcquireOrThrow} when the chosen provider has
 * exhausted one of its per-provider budget caps ({@code app.ai.budget.providers.<key>}).
 *
 * <p>Unchecked, like {@link BudgetExceededException}, so {@code LlmRouter}'s
 * per-handle {@code catch (RuntimeException)} fall-through routes the call to
 * the next provider instead of failing. The provider stays disabled for the
 * rest of the run; the application keeps running on the others.
 */
public class ProviderBudgetExhaustedException extends RuntimeException {

    private final String provider;

    public ProviderBudgetExhaustedException(String provider, String reason) {
        super("provider '" + provider + "' budget exhausted (" + reason + ") — routing around it");
        this.provider = provider;
    }

    public String provider() { return provider; }
}
