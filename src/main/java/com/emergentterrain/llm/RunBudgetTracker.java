package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Enforces the run-scoped LLM budget configured under {@code app.ai.budget}
 * (see {@link BudgetProperties}). Default OFF.
 *
 * <p>Tracks, per provider and globally, four cumulative dimensions over the
 * whole process run: calls, input tokens, output tokens, and dollar cost
 * (computed per model via {@link LlmPricing}).
 *
 * <ul>
 *   <li><b>Per-provider cap reached</b> → that provider is disabled; the pre-call
 *       gate then throws {@link ProviderBudgetExhaustedException} for it so
 *       {@code LlmRouter} routes around it to the other providers.</li>
 *   <li><b>Global ceiling reached</b>, or <b>every provider in use is exhausted</b>
 *       → the application is terminated (exit code {@value #EXIT_CODE}).</li>
 * </ul>
 *
 * <p>Calls are recorded from {@link TpmBudgetGate#tryAcquireOrThrow} (pre-call);
 * tokens/cost are recorded from the provider pools once a call completes (post-
 * call), independently of {@link TokenAccountant}'s per-minute reserve/correct
 * accounting so the two never double-count.
 */
@Component
public class RunBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(RunBudgetTracker.class);

    /** Exit code reported to the OS when the budget stops the run. */
    public static final int EXIT_CODE = 42;

    private final BudgetProperties props;
    private final LlmPricing pricing;
    private final ApplicationContext ctx;

    private static final class Acc {
        final AtomicLong calls  = new AtomicLong();
        final AtomicLong inTok  = new AtomicLong();
        final AtomicLong outTok = new AtomicLong();
        final DoubleAdder costUsd = new DoubleAdder();
        volatile boolean exhausted;
    }

    private final ConcurrentMap<String, Acc> byProvider = new ConcurrentHashMap<>();
    private final Acc global = new Acc();
    private final AtomicBoolean stopStarted = new AtomicBoolean(false);
    /**
     * 2026-06-05 — graceful drain. Set when global calls reach
     * {@code global.drainStartCalls} ({@code > 0}): new research cycles must not
     * start ({@link #allowNewResearch()}), in-flight papers keep spending until
     * done, and {@link #notifyQuiescent} stops the app cleanly once the caller's
     * in-flight queue empties. {@code maxCalls} remains the hard backstop — a
     * drain that never quiesces still terminates there. Motivated by
     * P-b72c-140/141 (2026-06-05): the hard stop killed the first two
     * beyond-sieve-computing papers mid-pipeline, spend already sunk.
     */
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final java.util.Set<String> warnedNoPrice = ConcurrentHashMap.newKeySet();

    /**
     * 2026-06-05 — auto-disable on an auth/billing failure streak. A provider whose
     * key is dead (401/402/403 — invalid, out of credit, "Key limit exceeded") fails
     * EVERY call, but the router's failover retried it all session: 366 zero-token
     * dispatches in 6 minutes burned the run budget to the drain threshold with one
     * paper out (openrouter, 2026-06-05 12:05). After {@value #AUTH_FAILURE_DISABLE_THRESHOLD}
     * consecutive auth-class failures the provider is disabled exactly like a
     * per-provider cap breach (routing skips it; the app stops only if every relevant
     * provider is gone). 429/5xx are transient and never count; any successful call
     * resets the streak.
     */
    static final int AUTH_FAILURE_DISABLE_THRESHOLD = 5;
    private final ConcurrentMap<String, AtomicLong> authFailureStreak = new ConcurrentHashMap<>();

    public RunBudgetTracker(BudgetProperties props, LlmPricing pricing, ApplicationContext ctx) {
        this.props = props;
        this.pricing = pricing;
        this.ctx = ctx;
    }

    private Acc acc(String provider) {
        return byProvider.computeIfAbsent(provider, k -> new Acc());
    }

    /**
     * Pre-call gate, before any token reservation: throw if the provider is
     * already disabled or a run-stop is underway, so routing skips it. No-op
     * when the feature is disabled. Does not count.
     */
    public void checkProviderAvailable(String provider) {
        if (!props.isEnabled() || provider == null) return;
        if (stopStarted.get()) {
            throw new ProviderBudgetExhaustedException(provider, "run budget reached, stopping");
        }
        Acc a = byProvider.get(provider);
        if (a != null && a.exhausted) {
            throw new ProviderBudgetExhaustedException(provider, "per-provider cap reached");
        }
    }

    /**
     * Pre-call, on the success path (the call is about to hit the network):
     * count one dispatched call and enforce the call caps. Marks the provider
     * exhausted on a per-provider cap, or stops the app on the global cap.
     */
    public void recordCall(String provider) {
        if (!props.isEnabled() || provider == null) return;
        long pc = acc(provider).calls.incrementAndGet();
        long gc = global.calls.incrementAndGet();

        BudgetProperties.Caps pcap = props.getProviders().get(provider);
        if (pcap != null && pcap.getMaxCalls() > 0 && pc >= pcap.getMaxCalls()) {
            disableProvider(provider, "calls " + pc + " >= " + pcap.getMaxCalls());
        }
        long drainStart = props.getGlobal().effectiveDrainStartCalls();
        if (drainStart > 0 && gc >= drainStart && !stopStarted.get()
                && draining.compareAndSet(false, true)) {
            log.warn("Run budget DRAINING (global calls {} >= drain-start {}): no new research "
                            + "cycles will start; in-flight papers complete on the remaining budget; "
                            + "clean stop when the in-flight queue empties (hard stop at {} calls).",
                    gc, drainStart, props.getGlobal().getMaxCalls());
        }
        if (props.getGlobal().getMaxCalls() > 0 && gc >= props.getGlobal().getMaxCalls()) {
            stop("global calls " + gc + " >= " + props.getGlobal().getMaxCalls());
        }
    }

    /**
     * True while new research cycles may start. False once draining or stopping —
     * the cycle scheduler ({@code ResearchCycleService.runCycle}) consults this
     * before launching a new paper pipeline. Always true when the budget feature
     * is disabled.
     */
    public boolean allowNewResearch() {
        return !props.isEnabled() || (!draining.get() && !stopStarted.get());
    }

    /** True once the drain threshold has been crossed (and until the stop). */
    public boolean isDraining() {
        return draining.get();
    }

    /**
     * Semantic alias of {@link #allowNewResearch()} for non-research background
     * consumers (policy consultations, government digests). 2026-06-05 drain
     * post-mortem: 33 of the 60-call reserve went to policy consultations,
     * starving the in-flight paper the drain existed to protect (P-b72c-145
     * lost at the hard stop). Discretionary chatter has no value in a draining
     * session — gate it on this.
     */
    public boolean allowDiscretionarySpend() {
        return allowNewResearch();
    }

    /**
     * Caller-side drain completion: invoked (e.g. from the in-flight-draft
     * sweep) when no work remains in flight while draining — stops the app
     * cleanly with the same exit code as the hard cap, so operator tooling
     * treats both ends identically. No-op unless draining; idempotent.
     */
    public void notifyQuiescent(String what) {
        if (draining.get() && !stopStarted.get()) {
            stop("drained — " + what);
        }
    }

    /**
     * Post-call: accumulate a completed call's tokens and dollar cost, then
     * enforce the token/cost caps. Per-provider breach disables the provider;
     * global breach stops the app. Does not throw — the call already happened.
     */
    /**
     * Report an auth/billing-class HTTP failure (401 unauthorized, 402 payment
     * required, 403 forbidden — e.g. OpenRouter "Key limit exceeded"). Called by the
     * provider pools from their non-200 branch. Disables the provider after
     * {@link #AUTH_FAILURE_DISABLE_THRESHOLD} consecutive occurrences. Other status
     * codes are ignored here (429 has its own backoff in the pools; 5xx are
     * transient).
     */
    public void recordAuthClassFailure(String provider, int statusCode) {
        recordAuthClassFailure(provider, statusCode, null);
    }

    /**
     * Overload carrying a snippet of the provider's error body, so the loud
     * operator alert below can include the actual reason and any manage-key URL
     * the provider returned (e.g. OpenRouter's "Key limit exceeded … Manage it
     * using https://openrouter.ai/…/keys/…").
     */
    public void recordAuthClassFailure(String provider, int statusCode, String detail) {
        if (!props.isEnabled() || provider == null) return;
        if (statusCode != 401 && statusCode != 402 && statusCode != 403) return;
        long streak = authFailureStreak
                .computeIfAbsent(provider, k -> new AtomicLong()).incrementAndGet();
        if (streak == AUTH_FAILURE_DISABLE_THRESHOLD) {
            // 2026-06-05 operator request — a LOUD, unmissable alert, emitted exactly
            // once per provider per run at the moment of auto-disable. The middle line
            // is deliberately addressed to log-REVIEWING processes (the paper-review
            // skill's operational-logs section, ops summaries, any agent grepping this
            // file): a dead key silently degrades every downstream metric, so it must
            // surface in their summaries, not just here.
            log.error("""

                    ╔═══════════════════════ LLM PROVIDER KEY/CREDIT FAILURE ═══════════════════════╗
                    ║ Provider '{}' returned HTTP {} on {} consecutive calls — API key invalid,
                    ║ spend limit, or credit limit reached. The provider is AUTO-DISABLED for the
                    ║ rest of this run; routing falls through to the remaining providers.
                    ║ Provider message: {}
                    ║ ►► ANY PROCESS OR AGENT REVIEWING THESE LOGS (paper-review operational-logs
                    ║ section, ops summaries, monitoring): REPORT THIS EVENT PROMINENTLY IN YOUR
                    ║ SUMMARY — papers produced this session ran without this provider.
                    ║ ►► OPERATOR ACTION REQUIRED: restore the key/credits, then restart.
                    ╚════════════════════════════════════════════════════════════════════════════════╝""",
                    provider, statusCode, streak,
                    detail == null || detail.isBlank() ? "(none captured)" : detail);
        }
        if (streak >= AUTH_FAILURE_DISABLE_THRESHOLD) {
            disableProvider(provider, "HTTP " + statusCode + " ×" + streak
                    + " consecutive — key invalid / limit exceeded? (auth-class auto-disable)");
        }
    }

    public void recordUsageAndEnforce(String provider, String model, long inputTokens, long outputTokens) {
        if (!props.isEnabled() || provider == null) return;
        // A completed call with usage = the provider's key works; reset the
        // auth-failure streak so transient blips never accumulate to a disable.
        authFailureStreak.remove(provider);

        double cost = pricing.costUsd(provider, model, inputTokens, outputTokens);
        if (!pricing.hasPrice(provider, model) && warnedNoPrice.add(provider + "/" + model)) {
            log.warn("No price for {}/{} — counted as $0 toward the budget. Add it to llm-pricing.yml.",
                    provider, model);
        }

        Acc a = acc(provider);
        long pin  = a.inTok.addAndGet(inputTokens);
        long pout = a.outTok.addAndGet(outputTokens);
        a.costUsd.add(cost);
        double pcost = a.costUsd.sum();

        long gin  = global.inTok.addAndGet(inputTokens);
        long gout = global.outTok.addAndGet(outputTokens);
        global.costUsd.add(cost);
        double gcost = global.costUsd.sum();

        // 2026-06-05 — cost-dimension drain, symmetric with the call-count drain in
        // recordCall. The 19:59 session hit the $5 ceiling at 326/500 calls (cost had
        // become the binding dimension) and the call-only drain never engaged —
        // hard stop, two papers lost in flight. Same shared DRAINING state, same
        // quiescent-stop path; the maxCostUsd breach below stays the hard backstop.
        double costDrainStart = props.getGlobal().effectiveDrainStartCostUsd();
        if (costDrainStart > 0 && gcost >= costDrainStart && !stopStarted.get()
                && draining.compareAndSet(false, true)) {
            log.warn("Run budget DRAINING (global cost ${} >= drain-start ${}): no new research "
                            + "cycles will start; in-flight papers complete on the remaining budget; "
                            + "clean stop when the in-flight queue empties (hard stop at ${}).",
                    String.format("%.4f", gcost), String.format("%.4f", costDrainStart),
                    String.format("%.2f", props.getGlobal().getMaxCostUsd()));
        }

        BudgetProperties.Caps pcap = props.getProviders().get(provider);
        if (pcap != null) {
            String why = breach(pcap, pin, pout, pcost);
            if (why != null) disableProvider(provider, why);
        }
        String gwhy = breach(props.getGlobal(), gin, gout, gcost);
        if (gwhy != null) stop("global " + gwhy);
    }

    /** Returns a human reason if any token/cost cap is met, else null. */
    private static String breach(BudgetProperties.Caps c, long in, long out, double cost) {
        if (c.getMaxInputTokens()  > 0 && in   >= c.getMaxInputTokens())  return "input tokens " + in + " >= " + c.getMaxInputTokens();
        if (c.getMaxOutputTokens() > 0 && out  >= c.getMaxOutputTokens()) return "output tokens " + out + " >= " + c.getMaxOutputTokens();
        if (c.getMaxCostUsd()      > 0 && cost >= c.getMaxCostUsd())      return String.format("cost $%.4f >= $%.4f", cost, c.getMaxCostUsd());
        return null;
    }

    private void disableProvider(String provider, String why) {
        Acc a = acc(provider);
        if (a.exhausted) return;
        a.exhausted = true;
        log.warn("Provider '{}' disabled — {}. Routing around it.", provider, why);
        if (allRelevantProvidersExhausted()) {
            stop("every provider is exhausted");
        }
    }

    /**
     * True once every <em>relevant</em> provider is exhausted, where relevant =
     * providers configured with a cap ∪ providers already used. So:
     * <ul>
     *   <li>a capped provider not yet used keeps the run alive until it too
     *       exhausts (no premature stop while it could still serve);</li>
     *   <li>an uncapped provider that has been used (e.g. a free OpenRouter
     *       tier) never exhausts, so the run stays alive on it indefinitely.</li>
     * </ul>
     */
    private boolean allRelevantProvidersExhausted() {
        java.util.Set<String> relevant = new java.util.HashSet<>(byProvider.keySet());
        for (Map.Entry<String, BudgetProperties.Caps> e : props.getProviders().entrySet()) {
            if (e.getValue().hasAnyCap()) relevant.add(e.getKey());
        }
        if (relevant.isEmpty()) return false;
        for (String p : relevant) {
            Acc a = byProvider.get(p);
            if (a == null || !a.exhausted) return false;
        }
        return true;
    }

    private void stop(String why) {
        if (!stopStarted.compareAndSet(false, true)) return;
        log.warn("Run budget reached ({}). Terminating application (exit code {}).", why, EXIT_CODE);
        terminate(EXIT_CODE);
    }

    /**
     * Terminate the JVM. Runs off the calling thread because the call hook
     * fires from inside the pools' call path and {@link SpringApplication#exit}
     * closes the context. Overridable so tests can intercept termination
     * instead of killing the test JVM.
     */
    protected void terminate(int exitCode) {
        Thread t = new Thread(() -> {
            int code = SpringApplication.exit(ctx, () -> exitCode);
            System.exit(code);
        }, "run-budget-stop");
        t.start();
    }

    /** True once a global ceiling or all-providers-exhausted stop has fired. */
    public boolean isStopRequested() {
        return stopStarted.get();
    }

    // -- operator visibility / metrics ------------------------------------

    public long callsFor(String provider)     { Acc a = byProvider.get(provider); return a == null ? 0L : a.calls.get(); }
    public double costUsdFor(String provider)  { Acc a = byProvider.get(provider); return a == null ? 0.0 : a.costUsd.sum(); }
    public boolean isExhausted(String provider){ Acc a = byProvider.get(provider); return a != null && a.exhausted; }
    public double totalCostUsd()               { return global.costUsd.sum(); }
    public long totalCalls()                   { return global.calls.get(); }

    /** Immutable snapshot of one accumulator (provider or global). */
    public record Usage(long calls, long inputTokens, long outputTokens, double costUsd, boolean exhausted) {}

    /** Per-provider usage snapshot, in first-seen order. Safe to read any time. */
    public Map<String, Usage> usageByProvider() {
        Map<String, Usage> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Acc> e : byProvider.entrySet()) {
            Acc a = e.getValue();
            out.put(e.getKey(), new Usage(a.calls.get(), a.inTok.get(), a.outTok.get(), a.costUsd.sum(), a.exhausted));
        }
        return out;
    }

    /** Aggregate usage across all providers this run. */
    public Usage globalUsage() {
        return new Usage(global.calls.get(), global.inTok.get(), global.outTok.get(), global.costUsd.sum(), false);
    }
}
