package com.emergentterrain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-provider token budgets and pause/resume thresholds for {@link SimulationGate}.
 *
 * Provider keys are free-form ({@code groq}, {@code ollama}, {@code anthropic}, …);
 * each entry can set {@code dailyLimit} and/or {@code minuteLimit} independently.
 * A limit of 0 means "unlimited / don't enforce" (e.g. local Ollama).
 *
 * The gate closes when any provider's rolling daily or minute spend crosses
 * {@code pauseThresholdPct} of its respective limit, and reopens only when
 * every provider falls back below {@code resumeThresholdPct} (hysteresis).
 *
 * <p>Phase 21 §21.1 — two-tier per-minute gate. {@code pauseThresholdPct}
 * (default 95%) is the HARD threshold and blocks every metered LLM call
 * (today's behavior). {@code softPauseThresholdPct} (default 85%) is a
 * SOFT threshold that {@link TpmBudgetGate} consults only when the caller
 * passes {@link TpmBudgetGate.GateLevel#SOFT} — used for "starting a new
 * tool-loop round" so an in-flight tool conversation can finish at >85%
 * without an extra round being attempted. Lower than the hard threshold,
 * by convention; not enforced.
 */
@Component
@ConfigurationProperties("app.tokens.budget")
public class TokenBudgetProperties {

    private boolean enabled = true;
    private int pauseThresholdPct = 95;
    private int softPauseThresholdPct = 85;
    private int resumeThresholdPct = 80;
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPauseThresholdPct() { return pauseThresholdPct; }
    public void setPauseThresholdPct(int pauseThresholdPct) { this.pauseThresholdPct = pauseThresholdPct; }

    public int getSoftPauseThresholdPct() { return softPauseThresholdPct; }
    public void setSoftPauseThresholdPct(int softPauseThresholdPct) { this.softPauseThresholdPct = softPauseThresholdPct; }

    public int getResumeThresholdPct() { return resumeThresholdPct; }
    public void setResumeThresholdPct(int resumeThresholdPct) { this.resumeThresholdPct = resumeThresholdPct; }

    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    public static class Provider {
        private long dailyLimit = 0;
        private long minuteLimit = 0;
        /** Optional per-provider override. -1 = inherit the global default. */
        private int pauseThresholdPct = -1;
        /** Optional per-provider override. -1 = inherit the global default. */
        private int softPauseThresholdPct = -1;

        public long getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(long dailyLimit) { this.dailyLimit = dailyLimit; }

        public long getMinuteLimit() { return minuteLimit; }
        public void setMinuteLimit(long minuteLimit) { this.minuteLimit = minuteLimit; }

        public int getPauseThresholdPct() { return pauseThresholdPct; }
        public void setPauseThresholdPct(int pauseThresholdPct) { this.pauseThresholdPct = pauseThresholdPct; }

        public int getSoftPauseThresholdPct() { return softPauseThresholdPct; }
        public void setSoftPauseThresholdPct(int softPauseThresholdPct) { this.softPauseThresholdPct = softPauseThresholdPct; }
    }
}
