package com.emergentterrain.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Phase 9 follow-on (2026-05-14) — periodic re-test of models that were
 * dropped by the initial selection test. Surface for transient failures
 * (NVIDIA's intermittent 503 ResourceExhausted, Cerebras's per-minute RPM
 * cap, OpenRouter backend hiccups, etc.) — a model dropped at startup gets
 * a fresh chance every {@code app.ai.selection-tests.retestIntervalMinutes}
 * (default 15) and rejoins the pool's active set on a passing probe.
 *
 * <p>Pull-based design: the service iterates every {@link LlmPool} bean's
 * {@link LlmPool#pendingRetests()} list, runs {@link ModelSelectionTester#test}
 * on each entry's dispatch lambdas, and on a passing result calls the pool's
 * {@link LlmPool#onModelRecovered(String)} hook. The pool is responsible
 * for adding the model back to its active set; the registries re-aggregate
 * pool model lists on every {@code availableModels()} call so recovered
 * models become routable immediately.
 *
 * <p>Disabled when {@code app.ai.selection-tests.enabled=false} — the
 * interval is irrelevant if no initial selection happened.
 */
@Service
public class ModelRetestService {

    private static final Logger log = LoggerFactory.getLogger(ModelRetestService.class);

    /**
     * Lower bound on the re-test interval. Operator direction 2026-05-14 —
     * "min 10 min". Re-tests burn provider quota; tighter than 10 min would
     * thrash quota for a benefit (recovering a transient-failed model)
     * that's tolerant of a longer wait.
     */
    static final int MIN_INTERVAL_MINUTES = 10;
    static final int DEFAULT_INTERVAL_MINUTES = 15;

    private final ApplicationContext ctx;
    private final boolean enabled;
    private final int intervalMinutes;
    private final ScheduledExecutorService scheduler;

    public ModelRetestService(
            ApplicationContext ctx,
            @Value("${app.ai.selection-tests.enabled:true}") boolean selectionTestsEnabled,
            @Value("${app.ai.selection-tests.retestIntervalMinutes:" + DEFAULT_INTERVAL_MINUTES + "}")
            int intervalMinutesCfg) {
        this.ctx     = ctx;
        this.enabled = selectionTestsEnabled;
        this.intervalMinutes = Math.max(MIN_INTERVAL_MINUTES, intervalMinutesCfg);
        if (intervalMinutesCfg < MIN_INTERVAL_MINUTES) {
            log.warn("ModelRetestService: configured interval {}min is below the floor {}min — using floor",
                    intervalMinutesCfg, MIN_INTERVAL_MINUTES);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "model-retest");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("ModelRetestService: selection-tests disabled — re-test loop not scheduled");
            return;
        }
        log.info("ModelRetestService: starting re-test loop every {} minutes", intervalMinutes);
        scheduler.scheduleAtFixedRate(this::runOnce,
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Pulls each pool's {@link LlmPool#pendingRetests()} and probes every
     * entry. Visible for unit-testing the iteration; production callers go
     * through the scheduler. Catches per-pool / per-model exceptions so a
     * single bad pool can't break the loop.
     */
    void runOnce() {
        int totalProbed = 0;
        int totalRecovered = 0;
        for (LlmPool pool : ctx.getBeansOfType(LlmPool.class).values()) {
            List<LlmPool.RetestableModel> pending;
            try {
                pending = pool.pendingRetests();
            } catch (Exception e) {
                log.warn("ModelRetestService: pool={} pendingRetests() threw — skipping: {}",
                        pool.providerKey(), e.getMessage());
                continue;
            }
            if (pending == null || pending.isEmpty()) continue;
            for (LlmPool.RetestableModel rm : pending) {
                totalProbed++;
                try {
                    ModelSelectionTester.SelectionResult r = ModelSelectionTester.test(
                            pool.providerKey() + "[retest]",
                            rm.modelId(),
                            rm.syncDispatch() == null ? null
                                    : prompt -> rm.syncDispatch().apply(rm.modelId(), prompt),
                            rm.toolDispatch());
                    if (r.passed()) {
                        pool.onModelRecovered(rm.modelId());
                        totalRecovered++;
                        log.info("ModelRetestService: pool={} model={} RECOVERED — added back to active set",
                                pool.providerKey(), rm.modelId());
                    }
                } catch (Exception e) {
                    log.warn("ModelRetestService: pool={} model={} re-probe threw: {}",
                            pool.providerKey(), rm.modelId(), e.getMessage());
                }
            }
        }
        if (totalProbed > 0) {
            log.info("ModelRetestService: re-test pass complete probed={} recovered={}",
                    totalProbed, totalRecovered);
        }
    }

    @PreDestroy
    void shutdown() { scheduler.shutdownNow(); }
}
