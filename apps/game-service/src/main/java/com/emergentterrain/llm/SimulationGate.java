package com.emergentterrain.llm;

import com.emergentterrain.service.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared kill-switch for simulation-advancing schedulers.
 *
 * Every {@code @Scheduled} method that drives the simulation forward (room
 * maintenance, future market ticks, replication cycles, …) should consult
 * {@link #isOpen()} at the top and return early when it returns {@code false}.
 * Persistence / flush / activity-reporter schedulers do NOT consult the gate —
 * bookkeeping should keep running while paused.
 *
 * The gate closes when any one of:
 *   <ul>
 *     <li><b>every</b> configured provider's rolling daily or minute spend
 *         exceeds {@code pauseThresholdPct} of its limit (pre-emptive — counts
 *         every model the provider exposes, e.g. all Groq 70B+ models in
 *         aggregate),</li>
 *     <li>the primary LlmPool reports every model is currently TPD-blocked
 *         ({@code router.primaryFullyExhausted()} — reactive 429-driven),</li>
 *   </ul>
 * and reopens only when both signals clear AND daily/minute spend falls below
 * {@code resumeThresholdPct} (hysteresis prevents flapping).
 *
 * <p>Phase 13 E1 — aggregation changed from {@code max} to {@code min} across
 * providers. REVIEW_REPORT refresh #1 §1 rec #9 named the failure: Groq hit
 * 95% daily, the old max-pct check closed the gate, and the simulation paused
 * for ~85 minutes despite NVIDIA / OpenRouter / Cerebras (per
 * {@code tool-calling.providers} and {@code review-panel.providers}) having
 * headroom. The new min-pct check mirrors {@code primaryFullyExhausted()} —
 * pause only when every budget-tracked provider crosses the threshold.
 * {@code lastReason} still names the hottest provider for operator visibility.
 *
 * Provider-agnostic by design: the accountant aggregates by provider name, the
 * properties are a {@code Map<provider, ProviderLimits>}, and the router is
 * looked up by interface — swapping Groq for Anthropic, or running both side
 * by side, needs only YAML and a pool that reports through {@link TokenAccountant}.
 */
@Component
public class SimulationGate {

    private static final Logger log = LoggerFactory.getLogger(SimulationGate.class);

    private final TokenBudgetProperties props;
    private final TokenAccountant accountant;
    private final LlmRouter router; // optional — null in tests or when AI is disabled

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String lastReason = "open";
    private volatile Instant resumeAt  = Instant.now();

    @Autowired
    public SimulationGate(TokenBudgetProperties props,
                          TokenAccountant accountant,
                          @Autowired(required = false) LlmRouter router) {
        this.props      = props;
        this.accountant = accountant;
        this.router     = router;
        log.info("SimulationGate: enabled={} pause={}% resume={}% providers={}",
                props.isEnabled(),
                props.getPauseThresholdPct(),
                props.getResumeThresholdPct(),
                props.getProviders().keySet());
    }

    /** True when simulation should advance. False when paused. */
    public boolean isOpen() {
        if (!props.isEnabled()) {
            closed.set(false);
            lastReason = "budget disabled";
            return true;
        }

        // Reactive signal: primary pool has nothing healthy to call right now.
        if (router != null && router.primaryFullyExhausted()) {
            closed.set(true);
            lastReason = "primary LLM fully TPD-exhausted";
            resumeAt   = router.earliestPrimaryRecovery();
            return false;
        }

        // Pre-emptive signal: per-provider budget pct.
        // Phase 13 E1 — gate decision uses the COOLEST (min) provider spend
        // across metered providers. lastReason still names the hottest for
        // operator visibility, but the gate only closes when even the
        // coolest provider is above the pause threshold — i.e. fallback
        // capacity is genuinely exhausted across the budget-tracked fleet.
        ProviderSpread spread = providerSpread();

        if (closed.get()) {
            if (spread.coolestPct < props.getResumeThresholdPct()) {
                closed.set(false);
                lastReason = String.format(
                        "reopened: coolest=%s @ %.0f%% < resume=%d%% (hottest=%s @ %.0f%%)",
                        spread.coolestProvider, spread.coolestPct, props.getResumeThresholdPct(),
                        spread.hottestProvider, spread.hottestPct);
                log.info("SimulationGate: {}", lastReason);
                return true;
            }
            lastReason = String.format(
                    "paused: every provider >= %d%% (hottest=%s @ %.0f%%, coolest=%s @ %.0f%%, resume<%d%%)",
                    props.getPauseThresholdPct(),
                    spread.hottestProvider, spread.hottestPct,
                    spread.coolestProvider, spread.coolestPct,
                    props.getResumeThresholdPct());
            return false;
        }

        if (spread.coolestPct >= props.getPauseThresholdPct()) {
            closed.set(true);
            lastReason = String.format(
                    "paused: every provider >= %d%% (hottest=%s @ %.0f%%, coolest=%s @ %.0f%%)",
                    props.getPauseThresholdPct(),
                    spread.hottestProvider, spread.hottestPct,
                    spread.coolestProvider, spread.coolestPct);
            log.warn("SimulationGate: {}", lastReason);
            return false;
        }

        lastReason = String.format(
                "open: hottest=%s @ %.0f%% (coolest=%s @ %.0f%%)",
                spread.hottestProvider, spread.hottestPct,
                spread.coolestProvider, spread.coolestPct);
        return true;
    }

    public String  reason()   { return lastReason; }
    public Instant resumeAt() { return resumeAt; }

    /**
     * Phase 13 E1 — compute both the coolest and hottest metered provider
     * percentages in a single pass. Unmetered providers (daily=0 AND minute=0)
     * are excluded — they have no budget to exhaust, so including them would
     * always pin coolest at 0% and effectively disable the pre-emptive
     * pause. When no metered provider is configured, both ends are
     * {@code (provider="(none)", pct=0)} and the gate behaves as if budget
     * tracking were disabled.
     *
     * <p>The hottest is preserved purely for operator visibility in the
     * {@code lastReason} string; the gate-close decision uses the coolest.
     */
    ProviderSpread providerSpread() {
        double minPct = Double.POSITIVE_INFINITY;
        double maxPct = 0;
        String coolest = "(none)";
        String hottest = "(none)";
        for (var entry : props.getProviders().entrySet()) {
            String name = entry.getKey();
            TokenBudgetProperties.Provider p = entry.getValue();
            // For each metered provider, take its worst (max of day-pct and
            // minute-pct) as that provider's effective utilisation. Then take
            // the min across providers.
            double providerWorst = -1;
            String providerWorstSlot = null;
            if (p.getDailyLimit() > 0) {
                double pct = 100.0 * accountant.dailyTotal(name) / p.getDailyLimit();
                if (pct > providerWorst) { providerWorst = pct; providerWorstSlot = name + "/day"; }
            }
            if (p.getMinuteLimit() > 0) {
                double pct = 100.0 * accountant.minuteTotal(name) / p.getMinuteLimit();
                if (pct > providerWorst) { providerWorst = pct; providerWorstSlot = name + "/min"; }
            }
            if (providerWorst < 0) continue; // unmetered — skip
            if (providerWorst < minPct) { minPct = providerWorst; coolest = providerWorstSlot; }
            if (providerWorst > maxPct) { maxPct = providerWorst; hottest = providerWorstSlot; }
        }
        if (minPct == Double.POSITIVE_INFINITY) minPct = 0;
        return new ProviderSpread(coolest, minPct, hottest, maxPct);
    }

    record ProviderSpread(String coolestProvider, double coolestPct,
                          String hottestProvider, double hottestPct) {}
}
