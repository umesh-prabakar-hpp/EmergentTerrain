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
 *     <li>any configured provider's rolling daily or minute spend exceeds
 *         {@code pauseThresholdPct} of its limit (pre-emptive — counts every
 *         model the provider exposes, e.g. all Groq 70B+ models in aggregate),</li>
 *     <li>the primary LlmPool reports every model is currently TPD-blocked
 *         ({@code router.primaryFullyExhausted()} — reactive 429-driven),</li>
 *   </ul>
 * and reopens only when both signals clear AND daily/minute spend falls below
 * {@code resumeThresholdPct} (hysteresis prevents flapping).
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
        HottestSpend hot = hottestSpend();

        if (closed.get()) {
            if (hot.pct < props.getResumeThresholdPct()) {
                closed.set(false);
                lastReason = String.format("reopened: hottest=%s @ %.0f%% < resume=%d%%",
                        hot.provider, hot.pct, props.getResumeThresholdPct());
                log.info("SimulationGate: {}", lastReason);
                return true;
            }
            lastReason = String.format("paused: %s @ %.0f%% (resume<%d%%)",
                    hot.provider, hot.pct, props.getResumeThresholdPct());
            return false;
        }

        if (hot.pct >= props.getPauseThresholdPct()) {
            closed.set(true);
            lastReason = String.format("paused: %s @ %.0f%% >= pause=%d%%",
                    hot.provider, hot.pct, props.getPauseThresholdPct());
            log.warn("SimulationGate: {}", lastReason);
            return false;
        }

        lastReason = String.format("open: hottest=%s @ %.0f%%", hot.provider, hot.pct);
        return true;
    }

    public String  reason()   { return lastReason; }
    public Instant resumeAt() { return resumeAt; }

    private HottestSpend hottestSpend() {
        double maxPct = 0;
        String which  = "(none)";
        for (var entry : props.getProviders().entrySet()) {
            String name = entry.getKey();
            TokenBudgetProperties.Provider p = entry.getValue();
            if (p.getDailyLimit() > 0) {
                double pct = 100.0 * accountant.dailyTotal(name) / p.getDailyLimit();
                if (pct > maxPct) { maxPct = pct; which = name + "/day"; }
            }
            if (p.getMinuteLimit() > 0) {
                double pct = 100.0 * accountant.minuteTotal(name) / p.getMinuteLimit();
                if (pct > maxPct) { maxPct = pct; which = name + "/min"; }
            }
        }
        return new HottestSpend(which, maxPct);
    }

    private record HottestSpend(String provider, double pct) {}
}
