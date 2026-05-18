package com.emergentterrain.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Per-call counter for LLM chat completions, tagged with
 * {@code provider}, {@code model}, {@code mode} (sync|stream|tools) and
 * {@code outcome} (ok|http_error|io_error). Scrape via
 * {@code GET /actuator/metrics/llm.calls}.
 *
 * <p>Lives separately from {@link TokenAccountant} on purpose: that bean only
 * runs after a successful 200 with a parseable {@code usage} field, so any
 * call that errors out before {@code recordUsage} is invisible there. This
 * counter increments unconditionally — once per attempted chat completion.
 */
@Component
public class LlmCallMetrics {

    public static final String METRIC = "llm.calls";

    private final MeterRegistry registry;

    public LlmCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String provider, String model, String mode, String outcome) {
        registry.counter(METRIC, Tags.of(
                "provider", provider == null ? "unknown" : provider,
                "model",    model    == null ? "unknown" : model,
                "mode",     mode     == null ? "unknown" : mode,
                "outcome",  outcome  == null ? "unknown" : outcome
        )).increment();
    }
}
