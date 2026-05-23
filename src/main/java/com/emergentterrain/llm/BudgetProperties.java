package com.emergentterrain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run-scoped LLM budget, bound from {@code app.ai.budget}. Default OFF.
 *
 * <p>Two levels, each a {@link Caps} with four independent dimensions
 * (calls, input tokens, output tokens, dollar cost). A cap is active only when
 * its value is positive ({@code <= 0} = unlimited):
 * <ul>
 *   <li>{@code global} — ceilings across all providers. Reaching any one stops
 *       the application.</li>
 *   <li>{@code providers.<key>} — per-provider caps. Reaching any one disables
 *       that provider (routing skips it). The app stops only on a global
 *       ceiling or when every configured provider is exhausted.</li>
 * </ul>
 *
 * <p>Dollar cost is computed per model from {@link LlmPricing}. Enforcement
 * lives in {@link RunBudgetTracker}.
 */
@Component
@ConfigurationProperties("app.ai.budget")
public class BudgetProperties {

    /** Master switch. Default OFF — no budget, today's behaviour. */
    private boolean enabled = false;

    private Caps global = new Caps();
    private Map<String, Caps> providers = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Caps getGlobal() { return global; }
    public void setGlobal(Caps global) { this.global = global; }

    public Map<String, Caps> getProviders() { return providers; }
    public void setProviders(Map<String, Caps> providers) { this.providers = providers; }

    /** One set of caps. Each {@code <= 0} means "unlimited / don't enforce". */
    public static class Caps {
        private double maxCostUsd = 0;
        private long maxCalls = 0;
        private long maxInputTokens = 0;
        private long maxOutputTokens = 0;

        public double getMaxCostUsd() { return maxCostUsd; }
        public void setMaxCostUsd(double v) { this.maxCostUsd = v; }
        public long getMaxCalls() { return maxCalls; }
        public void setMaxCalls(long v) { this.maxCalls = v; }
        public long getMaxInputTokens() { return maxInputTokens; }
        public void setMaxInputTokens(long v) { this.maxInputTokens = v; }
        public long getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(long v) { this.maxOutputTokens = v; }

        /** True if any dimension is configured (positive). */
        public boolean hasAnyCap() {
            return maxCostUsd > 0 || maxCalls > 0 || maxInputTokens > 0 || maxOutputTokens > 0;
        }
    }
}
