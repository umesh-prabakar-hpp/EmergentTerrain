package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces the process-wide LLM budgets configured under {@code app.ai.call-limit}
 * (see {@link CallLimitProperties}). Default OFF.
 *
 * <p>Scope is the whole application run — two global odometers over the process
 * lifetime, NOT per-paper:
 * <ul>
 *   <li><b>calls</b> — one per request that clears the budget gate and is about
 *       to hit a provider. Counted pre-call from {@link #recordCallAndEnforce()}.</li>
 *   <li><b>tokens</b> — prompt + completion of every completed request. Counted
 *       post-call from {@link #recordTokensAndEnforce(long)}.</li>
 * </ul>
 *
 * <p>When <em>either</em> cap is exceeded the application is terminated. The
 * call counter is driven from {@link TpmBudgetGate#tryAcquireOrThrow} and the
 * token counter from {@link TokenAccountant#record} — the two universal
 * chokepoints shared by all remote provider pools — so no pool code changes.
 */
@Component
public class SessionCallLimit {

    private static final Logger log = LoggerFactory.getLogger(SessionCallLimit.class);

    /** Exit code reported to the OS when a cap trips. */
    public static final int EXIT_CODE = 42;

    private final CallLimitProperties props;
    private final ApplicationContext ctx;

    private final AtomicLong callCount  = new AtomicLong();
    private final AtomicLong tokenCount = new AtomicLong();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public SessionCallLimit(CallLimitProperties props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    /**
     * Pre-call hook: count one outbound LLM call and enforce the call cap.
     * No-op when the feature is disabled. Throws {@link CallLimitExceededException}
     * (so the call never reaches the network) when the call cap is now exceeded,
     * or when a cap has already tripped and shutdown is underway.
     */
    public void recordCallAndEnforce() {
        if (!props.isEnabled()) {
            return;
        }
        if (shutdownStarted.get()) {
            // A cap already tripped — block any further network calls while the
            // application is shutting down.
            throw new CallLimitExceededException("cap tripped, shutdown in progress");
        }
        long n = callCount.incrementAndGet();
        if (props.getMaxCalls() > 0 && n > props.getMaxCalls()) {
            triggerShutdownOnce("call count " + n + " exceeded max-calls " + props.getMaxCalls());
            throw new CallLimitExceededException("call count " + n + " > max-calls " + props.getMaxCalls());
        }
    }

    /**
     * Post-call hook: add a completed call's tokens (prompt + completion) and
     * enforce the token cap. No-op when disabled or {@code tokens <= 0}. Does
     * not throw — the call has already completed, so there is nothing to block;
     * it just trips the (one-shot) shutdown when the token cap is exceeded.
     */
    public void recordTokensAndEnforce(long tokens) {
        if (!props.isEnabled() || tokens <= 0) {
            return;
        }
        long t = tokenCount.addAndGet(tokens);
        if (props.getMaxTokens() > 0 && t > props.getMaxTokens()) {
            triggerShutdownOnce("token count " + t + " exceeded max-tokens " + props.getMaxTokens());
        }
    }

    /** Total LLM calls dispatched since startup (operator visibility / metrics). */
    public long currentCallCount() {
        return callCount.get();
    }

    /** Total tokens (prompt + completion) since startup. */
    public long currentTokenCount() {
        return tokenCount.get();
    }

    private void triggerShutdownOnce(String why) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        log.warn("Session LLM cap reached ({}). Terminating application (exit code {}).", why, EXIT_CODE);
        // Shut down off the calling thread: tryAcquireOrThrow is synchronized on
        // the budget gate, and SpringApplication.exit closes the context (running
        // @PreDestroy hooks), which we must not do while holding that monitor.
        Thread t = new Thread(() -> {
            int code = SpringApplication.exit(ctx, () -> EXIT_CODE);
            System.exit(code);
        }, "call-limit-shutdown");
        t.start();
    }
}
