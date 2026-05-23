package com.emergentterrain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Process-wide cap on the total number of LLM calls dispatched per run.
 *
 * <p>Bound from {@code app.ai.call-limit} in {@code application.yml}, mirroring
 * the {@link TokenBudgetProperties} pattern. Default OFF — when disabled (or
 * when {@code maxCalls <= 0}) there is no cap and behaviour is unchanged.
 *
 * <p>The count is a global odometer over the whole process lifetime (NOT
 * per-paper). It is incremented from the single pre-call chokepoint
 * {@link TpmBudgetGate#tryAcquireOrThrow}, so it covers every remote provider
 * pool (Groq + the generic OpenAI-compatible pools) without touching pool code.
 * Local Ollama and startup model-discovery calls do not pass through that gate
 * and are therefore not counted.
 *
 * <p>Two independent caps: {@code maxCalls} (number of requests) and
 * {@code maxTokens} (prompt + completion tokens summed across the run).
 * When <em>either</em> is exceeded the application is terminated — see
 * {@link SessionCallLimit}. Each cap is active only when its value is positive.
 */
@Component
@ConfigurationProperties("app.ai.call-limit")
public class CallLimitProperties {

    /** Master switch. Default OFF — no caps, today's behaviour. */
    private boolean enabled = false;

    /** Max LLM calls permitted per process run. {@code <= 0} means unlimited. */
    private long maxCalls = -1;

    /** Max total tokens (prompt + completion) per process run. {@code <= 0} means unlimited. */
    private long maxTokens = -1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getMaxCalls() { return maxCalls; }
    public void setMaxCalls(long maxCalls) { this.maxCalls = maxCalls; }

    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
}
