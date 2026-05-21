package com.emergentterrain.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Per-call counter for LLM chat completions, tagged with
 * {@code provider}, {@code model}, {@code mode} (sync|stream|tools),
 * {@code outcome} (ok|http_error|io_error), and {@code role}
 * (narration|professor-tools|panel-review|fast-audit|unknown — whatever
 * {@link LlmCallContext#currentRole()} returns at record time). Scrape via
 * {@code GET /actuator/metrics/llm.calls}.
 *
 * <p>Lives separately from {@link TokenAccountant} on purpose: that bean only
 * runs after a successful 200 with a parseable {@code usage} field, so any
 * call that errors out before {@code recordUsage} is invisible there. This
 * counter increments unconditionally — once per attempted chat completion.
 *
 * <p>The {@code role} tag is read from {@link LlmCallContext} at increment
 * time rather than threaded through the pool API, so existing pool call sites
 * don't change. Calls outside any {@code withRole(...)} scope (model-selection
 * probes, sidecar reporters, retest scheduling) land under {@code role=unknown},
 * which is itself a useful signal — large {@code unknown} totals mean the
 * call originates from somewhere that should probably wrap a role scope.
 */
@Component
public class LlmCallMetrics {

    public static final String METRIC = "llm.calls";

    /**
     * Companion to {@link #METRIC} — cumulative prompt + completion tokens, tagged
     * with {@code provider}, {@code model}, {@code role} and
     * {@code kind} ({@code prompt} | {@code completion}). Bump from every pool's
     * {@code recordUsage} alongside the existing {@code TokenAccountant.record}
     * call; together they let the reporter answer "how many tokens did each
     * role burn" without crossing the provider/model boundary by hand.
     */
    public static final String METRIC_TOKENS = "llm.tokens";

    private final MeterRegistry registry;

    public LlmCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String provider, String model, String mode, String outcome) {
        String role = LlmCallContext.currentRole();
        registry.counter(METRIC, Tags.of(
                "provider", provider == null ? "unknown" : provider,
                "model",    model    == null ? "unknown" : model,
                "mode",     mode     == null ? "unknown" : mode,
                "outcome",  outcome  == null ? "unknown" : outcome,
                "role",     role     == null ? "unknown" : role
        )).increment();
    }

    /**
     * Record prompt + completion token counts for a single LLM call. Tagged
     * by {@code provider/model/role/kind}. Zero-valued sides are skipped so
     * the actuator endpoint doesn't accumulate empty bins. Role read from
     * {@link LlmCallContext#currentRole()} at record time, same as
     * {@link #record}.
     */
    public void recordTokens(String provider, String model, long promptTokens, long completionTokens) {
        String role = LlmCallContext.currentRole();
        String providerTag = provider == null ? "unknown" : provider;
        String modelTag    = model    == null ? "unknown" : model;
        String roleTag     = role     == null ? "unknown" : role;
        if (promptTokens > 0) {
            registry.counter(METRIC_TOKENS, Tags.of(
                    "provider", providerTag,
                    "model",    modelTag,
                    "kind",     "prompt",
                    "role",     roleTag
            )).increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter(METRIC_TOKENS, Tags.of(
                    "provider", providerTag,
                    "model",    modelTag,
                    "kind",     "completion",
                    "role",     roleTag
            )).increment(completionTokens);
        }
    }
}
