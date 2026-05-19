package com.emergentterrain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 20.3 — decides whether to embed the {@code tools} JSON spec on a
 * given tool-loop round. Pre-Phase-20.3 every round resent the full spec
 * (~2-4 KB of schema per round × 5 rounds × every paper × every model
 * attempt = ~7 MB/day of redundant tool-spec tokens on a 200-paper/day
 * workload). With caching enabled, only round 0 sends the spec; the
 * model retains tool awareness across the conversation, and we re-send
 * every {@code refresh-cadence} rounds as insurance.
 *
 * <h2>Provider compatibility caveat</h2>
 * Some OpenAI-compat providers documentation tool-stickiness behavior
 * (Groq's chat-completions doc explicitly does); others may require the
 * {@code tools} field on every round. The policy ships {@code off} by
 * default so the operator can verify provider behavior before flipping.
 * If a provider starts hallucinating tool names mid-conversation, raise
 * the cadence (force re-send more often) or disable for that pool.
 *
 * <h2>Configuration</h2>
 * <pre>
 * app.ai.cached-tool-schema:
 *   enabled: false              # default — every round sends tools
 *   refresh-cadence: 3          # when enabled, force-resend every Nth round (N=0 means never re-send)
 * </pre>
 *
 * <h2>Last-round semantics</h2>
 * The existing tool-loop logic in each pool drops the {@code tools}
 * field on the final round of {@code MAX_TOOL_ROUNDS} to signal "no more
 * tool calls allowed — produce the final synthesis." {@link #shouldSendTools}
 * preserves that semantic: when {@code lastRound} is true, return false
 * regardless of the cache setting.
 */
@Component
public class ToolSchemaCachePolicy {

    private final boolean enabled;
    private final int refreshCadence;

    public ToolSchemaCachePolicy(
            @Value("${app.ai.cached-tool-schema.enabled:false}") boolean enabled,
            @Value("${app.ai.cached-tool-schema.refresh-cadence:3}") int refreshCadence) {
        this.enabled = enabled;
        // Sanity: cadence < 2 would force-resend every round (== disabled). Clamp
        // to ≥ 2 when the flag is on so the operator gets the cache benefit they
        // configured for; the explicit "never re-send" mode is refreshCadence = 0.
        this.refreshCadence = refreshCadence < 0 ? 0 : refreshCadence;
    }

    /**
     * @param round     0-based round index within the current tool-loop
     *                  invocation. Round 0 is the first request to the LLM.
     * @param lastRound true on the final round of {@code MAX_TOOL_ROUNDS}
     *                  (existing per-pool constant); the tool loop drops
     *                  tools then to force the model to write a synthesis.
     * @return true when the pool should embed {@code tools} in the request
     *         body for this round; false when it should omit the field.
     */
    public boolean shouldSendTools(int round, boolean lastRound) {
        if (lastRound) return false;            // existing semantic — keep it.
        if (!enabled) return true;              // feature off — send every round (today's behavior).
        if (round == 0) return true;            // first round always sends the spec.
        if (refreshCadence == 0) return false;  // operator opted out of periodic re-send.
        return round % refreshCadence == 0;     // periodic re-send insurance.
    }

    /** Diagnostics — logged at startup so the operator can see the policy state. */
    public boolean isEnabled() { return enabled; }
    public int refreshCadence() { return refreshCadence; }
}
