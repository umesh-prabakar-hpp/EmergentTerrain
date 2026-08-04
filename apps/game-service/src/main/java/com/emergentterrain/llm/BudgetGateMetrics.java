package com.emergentterrain.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 21 §21.1 — per-minute counters for {@link TpmBudgetGate} REJECT
 * decisions and actual upstream 429/TPM responses. The two are recorded
 * side-by-side so the phantom-throttle ratio (REJECT / 429) becomes a
 * watchable scalar at {@code /actuator/metrics/tpm.budget.reject} and
 * {@code /actuator/metrics/tpm.budget.upstream_429}.
 *
 * <p>Acceptance per the phase doc: ratio drops below 5:1 over a 45-minute
 * corpus run (today 122:1).
 *
 * <p>The interface form (default impl + {@link #NOOP}) lets unit tests
 * construct {@link TpmBudgetGate} without wiring Micrometer.
 */
public interface BudgetGateMetrics {

    /**
     * Record a pre-debit REJECT decision. Tagged by provider and gate level
     * (HARD = today's 95% wall; SOFT = the new 85% start-new-tool-round
     * check from Phase 21 §21.1).
     */
    void recordReject(String provider, TpmBudgetGate.GateLevel level);

    /**
     * Record an actual upstream 429/TPM response from a provider. Pools
     * call this from their 429-handling branch alongside their existing
     * {@code rateLimitExpiry} write. Counted separately from REJECTs so
     * the phantom-throttle ratio is computable as a meter-to-meter quotient.
     */
    void recordUpstream429(String provider);

    /** No-op implementation for tests that don't wire Micrometer. */
    BudgetGateMetrics NOOP = new BudgetGateMetrics() {
        @Override public void recordReject(String provider, TpmBudgetGate.GateLevel level) {}
        @Override public void recordUpstream429(String provider) {}
    };

    /**
     * Micrometer-backed default. Marked {@code @Primary} so application
     * wiring picks this implementation; tests may pass {@link #NOOP}
     * explicitly via the {@link TpmBudgetGate} two-arg constructor.
     */
    @Component
    @Primary
    class Default implements BudgetGateMetrics {

        public static final String METRIC_REJECT       = "tpm.budget.reject";
        public static final String METRIC_UPSTREAM_429 = "tpm.budget.upstream_429";

        private final MeterRegistry registry;

        public Default(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void recordReject(String provider, TpmBudgetGate.GateLevel level) {
            registry.counter(METRIC_REJECT, Tags.of(
                    "provider", provider == null ? "unknown" : provider,
                    "level",    level    == null ? "HARD"    : level.name()
            )).increment();
        }

        @Override
        public void recordUpstream429(String provider) {
            registry.counter(METRIC_UPSTREAM_429, Tags.of(
                    "provider", provider == null ? "unknown" : provider
            )).increment();
        }
    }
}
