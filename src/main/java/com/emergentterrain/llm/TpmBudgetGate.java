package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Phase 9 G9 — pre-debit budget gate for LLM calls.
 *
 * <p>The existing {@link SimulationGate} is a per-tick kill-switch consulted
 * at the top of {@code @Scheduled} methods and before narration stages. It
 * cannot prevent a burst of parallel virtual threads from each passing the
 * gate check and then collectively spending past the per-minute budget —
 * observed at 172% in the research/a3289005 logs. {@link TokenAccountant}
 * only learned about spend AFTER the HTTP response, so concurrent in-flight
 * calls were invisible to the budget.
 *
 * <p>This component closes that gap. Each LLM call invokes
 * {@link #tryAcquire(String, String, long)} BEFORE issuing the HTTP request,
 * passing a conservative estimate (input tokens + max output tokens). The
 * gate atomically:
 * <ol>
 *   <li>Reads the current per-minute total from {@link TokenAccountant}.</li>
 *   <li>Checks {@code current + estimate} against
 *       {@code minuteLimit × pauseThresholdPct%}.</li>
 *   <li>If it would exceed: returns {@code false} (caller must abort).</li>
 *   <li>Else: pre-records the estimate via
 *       {@link TokenAccountant#recordDelta} so concurrent threads see it
 *       immediately.</li>
 * </ol>
 *
 * <p>After the call completes, the caller invokes
 * {@link #correct(String, String, long, long)} with the actual usage from
 * the API response. This records {@code (actual − estimate)} so the budget
 * converges on real spend once the response arrives.
 *
 * <p>Unmetered providers ({@code minuteLimit ≤ 0}) always pass
 * {@code tryAcquire} and don't record reservations — the gate is a no-op
 * for them.
 */
@Component
public class TpmBudgetGate {

    private static final Logger log = LoggerFactory.getLogger(TpmBudgetGate.class);

    private final TokenAccountant accountant;
    private final TokenBudgetProperties props;

    @Autowired
    public TpmBudgetGate(TokenAccountant accountant, TokenBudgetProperties props) {
        this.accountant = accountant;
        this.props = props;
    }

    /**
     * Atomically check-and-pre-debit. Returns {@code true} iff the estimate
     * fits within {@code pauseThresholdPct} of the per-minute budget;
     * pre-records the estimate as a tentative spend so concurrent callers
     * immediately see the reservation.
     *
     * <p>Returns {@code true} (without recording) for unmetered providers
     * ({@code minuteLimit ≤ 0}) and for non-positive estimates.
     *
     * <p>{@code synchronized} on the gate instance, NOT on the accountant —
     * pre-check + pre-record happen as one atomic decision so two threads
     * cannot both observe "fits" and then both record.
     */
    public synchronized boolean tryAcquire(String provider, String model, long estimate) {
        if (estimate <= 0 || provider == null) return true;
        TokenBudgetProperties.Provider p = props.getProviders().get(provider);
        if (p == null || p.getMinuteLimit() <= 0) return true;  // unmetered

        long pauseAt = p.getMinuteLimit() * props.getPauseThresholdPct() / 100L;
        long current = accountant.minuteTotal(provider);
        if (current + estimate > pauseAt) {
            log.debug("TpmBudgetGate: provider={} model={} REJECT estimate={} current={} pauseAt={}",
                    provider, model, estimate, current, pauseAt);
            return false;
        }
        accountant.recordDelta(provider, estimate);
        if (log.isTraceEnabled()) {
            log.trace("TpmBudgetGate: provider={} model={} RESERVE estimate={} → currentAfter={} pauseAt={}",
                    provider, model, estimate, current + estimate, pauseAt);
        }
        return true;
    }

    /**
     * Convenience wrapper that throws {@link BudgetExceededException} when
     * {@link #tryAcquire} would return false. Provided so callers can fail
     * fast with a single line instead of an {@code if} branch.
     */
    public void tryAcquireOrThrow(String provider, String model, long estimate) {
        if (tryAcquire(provider, model, estimate)) return;
        TokenBudgetProperties.Provider p = props.getProviders().get(provider);
        long pauseAt = (p != null && p.getMinuteLimit() > 0)
                ? p.getMinuteLimit() * props.getPauseThresholdPct() / 100L
                : 0L;
        throw new BudgetExceededException(provider, estimate, accountant.minuteTotal(provider), pauseAt);
    }

    /**
     * Adjust a prior reservation to match actual usage. Records the delta
     * {@code (actual − estimate)} so {@link TokenAccountant#minuteTotal} now
     * reflects the actual spend, not the conservative estimate.
     *
     * <p>No-op when the provider is unmetered or when the delta is zero.
     */
    public void correct(String provider, String model, long estimate, long actual) {
        if (provider == null) return;
        long delta = actual - estimate;
        if (delta == 0) return;
        TokenBudgetProperties.Provider p = props.getProviders().get(provider);
        if (p == null || p.getMinuteLimit() <= 0) return;  // unmetered
        accountant.recordDelta(provider, delta);
        if (log.isTraceEnabled()) {
            log.trace("TpmBudgetGate: provider={} model={} CORRECT estimate={} actual={} delta={}",
                    provider, model, estimate, actual, delta);
        }
    }
}
