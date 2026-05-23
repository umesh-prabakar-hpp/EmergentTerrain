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
 *
 * <p><b>Phase 21 §21.1 — soft / hard split.</b> The gate exposes two
 * thresholds. {@link GateLevel#HARD} (default for the single-arg methods)
 * checks against {@code pauseThresholdPct} — every metered call must clear
 * it. {@link GateLevel#SOFT} checks against {@code softPauseThresholdPct}
 * (a lower percentage) and is meant for callers about to start a NEW tool-
 * loop round: when the gate has reached the soft threshold but not the
 * hard threshold, single-shot calls still go through but tool loops
 * exit gracefully instead of attempting another round and amplifying the
 * phantom-throttle storm observed in the research/a3289005 logs (244
 * phantom REJECTs vs 2 actual upstream TPM 429s, ratio 122:1).
 */
@Component
public class TpmBudgetGate {

    private static final Logger log = LoggerFactory.getLogger(TpmBudgetGate.class);

    /**
     * Pause-threshold tier consulted by {@link #tryAcquire}. {@link #HARD}
     * (today's behavior) is the firm wall on every metered call. {@link #SOFT}
     * is a lower threshold meant for "start a new tool-loop round" decisions
     * so an in-flight loop doesn't pile on more rounds when the budget is
     * already most-of-the-way full.
     */
    public enum GateLevel { HARD, SOFT }

    private final TokenAccountant accountant;
    private final TokenBudgetProperties props;
    private final BudgetGateMetrics metrics;
    /** Process-wide LLM call cap; {@code null} in tests (cap not enforced). */
    private final SessionCallLimit callLimit;

    @Autowired
    public TpmBudgetGate(TokenAccountant accountant,
                         TokenBudgetProperties props,
                         BudgetGateMetrics metrics,
                         SessionCallLimit callLimit) {
        this.accountant = accountant;
        this.props = props;
        this.metrics = metrics;
        this.callLimit = callLimit;
    }

    /** Test constructor — metrics + call-limit are optional. */
    public TpmBudgetGate(TokenAccountant accountant, TokenBudgetProperties props, BudgetGateMetrics metrics) {
        this(accountant, props, metrics, null);
    }

    /** Test constructor — metrics defaults to no-op and call-limit is disabled. */
    public TpmBudgetGate(TokenAccountant accountant, TokenBudgetProperties props) {
        this(accountant, props, BudgetGateMetrics.NOOP, null);
    }

    /**
     * Atomically check-and-pre-debit at {@link GateLevel#HARD}. See the
     * {@link #tryAcquire(String, String, long, GateLevel) four-arg overload}
     * for full semantics; this single-arg form preserves the pre-Phase-21
     * call signature.
     */
    public boolean tryAcquire(String provider, String model, long estimate) {
        return tryAcquire(provider, model, estimate, GateLevel.HARD);
    }

    /**
     * Atomically check-and-pre-debit. Returns {@code true} iff the estimate
     * fits within the requested {@code level}'s percentage of the per-minute
     * budget; pre-records the estimate as a tentative spend so concurrent
     * callers immediately see the reservation.
     *
     * <p>Returns {@code true} (without recording) for unmetered providers
     * ({@code minuteLimit ≤ 0}) and for non-positive estimates.
     *
     * <p>{@code synchronized} on the gate instance, NOT on the accountant —
     * pre-check + pre-record happen as one atomic decision so two threads
     * cannot both observe "fits" and then both record.
     */
    public synchronized boolean tryAcquire(String provider, String model, long estimate, GateLevel level) {
        if (estimate <= 0 || provider == null) return true;
        TokenBudgetProperties.Provider p = props.getProviders().get(provider);
        if (p == null || p.getMinuteLimit() <= 0) return true;  // unmetered

        long pauseAt = pauseAtFor(p, level);
        long current = accountant.minuteTotal(provider);
        if (current + estimate > pauseAt) {
            log.debug("TpmBudgetGate: provider={} model={} level={} REJECT estimate={} current={} pauseAt={}",
                    provider, model, level, estimate, current, pauseAt);
            metrics.recordReject(provider, level);
            return false;
        }
        accountant.recordDelta(provider, estimate);
        if (log.isTraceEnabled()) {
            log.trace("TpmBudgetGate: provider={} model={} level={} RESERVE estimate={} → currentAfter={} pauseAt={}",
                    provider, model, level, estimate, current + estimate, pauseAt);
        }
        return true;
    }

    /**
     * Convenience wrapper that throws {@link BudgetExceededException} when
     * {@link #tryAcquire} would return false at {@link GateLevel#HARD}.
     */
    public void tryAcquireOrThrow(String provider, String model, long estimate) {
        tryAcquireOrThrow(provider, model, estimate, GateLevel.HARD);
    }

    /**
     * Convenience wrapper that throws {@link BudgetExceededException} when
     * {@link #tryAcquire} would return false at the requested level.
     */
    public void tryAcquireOrThrow(String provider, String model, long estimate, GateLevel level) {
        if (tryAcquire(provider, model, estimate, level)) {
            // Call cleared the budget — it will hit the network, so count it
            // against the process-wide cap. Throws (and starts shutdown) if the
            // cap is now exceeded, before any HTTP request is made.
            if (callLimit != null) callLimit.recordCallAndEnforce();
            return;
        }
        TokenBudgetProperties.Provider p = props.getProviders().get(provider);
        long pauseAt = (p != null && p.getMinuteLimit() > 0) ? pauseAtFor(p, level) : 0L;
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

    /**
     * Resolve the effective pauseAt for a (provider, level) pair. Per-provider
     * overrides win when ≥ 0; otherwise the global default applies.
     */
    private long pauseAtFor(TokenBudgetProperties.Provider p, GateLevel level) {
        int pct = (level == GateLevel.SOFT)
                ? (p.getSoftPauseThresholdPct() >= 0 ? p.getSoftPauseThresholdPct() : props.getSoftPauseThresholdPct())
                : (p.getPauseThresholdPct()     >= 0 ? p.getPauseThresholdPct()     : props.getPauseThresholdPct());
        return p.getMinuteLimit() * pct / 100L;
    }
}
