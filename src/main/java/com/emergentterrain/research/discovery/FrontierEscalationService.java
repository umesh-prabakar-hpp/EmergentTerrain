package com.emergentterrain.research.discovery;

import com.emergentterrain.research.conjecture.ConjectureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 1 — the escalation skeleton
 * (phase-30-conjecture/frontier-escalation-loop.md §2). Given a bound that holds
 * across the mined scales, it escalates the scale {@code scaleFactor}× per round,
 * computing the studied quantity from the sieve (x ≤ NT_MAX_N) or from validated
 * runtime discovery (x beyond it), classifying each rung against the bound, and
 * accumulating a checkpoint corpus until it hits a counterexample, a
 * can't-compute stall, or the round cap.
 *
 * <p><b>Skeleton scope:</b> stall is handled reactively (a {@code CANNOT_COMPUTE}
 * stops the loop). The Phase-2 stall <i>predictor</i> and the Phase-3 branch
 * <i>pivot</i> are not here yet. Flag-gated OFF
 * ({@code app.research.discovery.frontierEscalationEnabled}); when off,
 * {@link #escalate} returns {@link EscalationOutcome#disabled()} and neither the
 * sieve source nor discovery is touched.
 */
@Service
public class FrontierEscalationService {

    private static final Logger log = LoggerFactory.getLogger(FrontierEscalationService.class);

    private final boolean enabled;
    private final int maxRounds;
    private final double stallBudgetMs;
    private final double scienceFrontier;
    private final MertensDiscoveryService discovery;

    public FrontierEscalationService(
            @Value("${app.research.discovery.frontierEscalationEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.frontierEscalationMaxRounds:8}") int maxRounds,
            @Value("${app.research.discovery.frontierStallBudgetMs:30000}") double stallBudgetMs,
            @Value("${app.research.discovery.frontierScienceFrontier:0}") double scienceFrontier,
            MertensDiscoveryService discovery) {
        this.enabled = enabled;
        this.maxRounds = Math.max(1, maxRounds);
        this.stallBudgetMs = stallBudgetMs;
        this.scienceFrontier = scienceFrontier;
        this.discovery = discovery;
        log.info("FrontierEscalationService ready — frontierEscalation={} maxRounds={} stallBudgetMs={} scienceFrontier={}",
                enabled ? "ON" : "OFF", this.maxRounds, stallBudgetMs, scienceFrontier);
    }

    /**
     * Phase 3 — optional branch pivot. Set by Spring when a {@link BranchPivotService}
     * bean exists (always, but gated by its own flag); null in unit tests that don't
     * exercise the pivot (the loop then degrades to a {@code predicted_stall} stop).
     */
    private BranchPivotService branchPivot;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBranchPivot(BranchPivotService branchPivot) {
        this.branchPivot = branchPivot;
    }

    /**
     * Phase 6 — the step policy. Default fixed ×10; the aggressive
     * {@link AdaptiveNextScalePolicy} when {@code frontierAdaptiveScaleEnabled} is
     * on. Spring calls this after construction; tests may call
     * {@link #setScalePolicy} directly.
     */
    private NextScalePolicy scalePolicy = new DefaultNextScalePolicy();

    @org.springframework.beans.factory.annotation.Autowired
    public void configureScalePolicy(
            @Value("${app.research.discovery.frontierAdaptiveScaleEnabled:false}") boolean adaptive) {
        this.scalePolicy = adaptive ? new AdaptiveNextScalePolicy() : new DefaultNextScalePolicy();
        log.info("FrontierEscalationService scale policy — {}",
                adaptive ? "ADAPTIVE (aggressive)" : "DEFAULT (fixed x10)");
    }

    /** Test seam — inject a policy directly. */
    void setScalePolicy(NextScalePolicy scalePolicy) {
        this.scalePolicy = scalePolicy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Escalate {@code bound} over {@code task}, drawing the quantity from
     * {@code sieveSource} (x ≤ NT_MAX_N) or discovery (beyond). Returns the
     * checkpoint corpus + terminal state.
     */
    public EscalationOutcome escalate(EscalationTask task, ScaleValueSource sieveSource, BoundPredicate bound) {
        if (!enabled) {
            return EscalationOutcome.disabled();
        }
        List<EscalationCheckpoint> cps = new ArrayList<>();
        List<CostObservation> costs = new ArrayList<>();   // discovery rungs only — fed to the predictor
        StallPrediction lastPrediction = null;
        long x = task.startScale();
        long frontier = 0;
        for (int round = 0; round < maxRounds; round++) {
            EscalationCheckpoint cp = evaluateAt(x, task, sieveSource, bound);
            cps.add(cp);
            log.info("escalation round {}/{} — x={} value={} verdict={} via {} ({}ms)",
                    round + 1, maxRounds, x, cp.value(), cp.verdict(), cp.source(), cp.elapsedMs());
            switch (cp.verdict()) {
                case COUNTEREXAMPLE -> {
                    return EscalationOutcome.counterexample(cps, frontier);
                }
                case CANNOT_COMPUTE -> {
                    return EscalationOutcome.stalled(cps, frontier);
                }
                case HOLDS -> frontier = x;
            }
            if ("discovery".equals(cp.source()) && cp.elapsedMs() > 0) {
                costs.add(new CostObservation(x, cp.elapsedMs()));
            }
            // Phase 2 — forecast the cost (before choosing the next scale, so the
            // policy can see the compute frontier).
            StallPrediction pred = StallPredictor.predict(costs, stallBudgetMs, scienceFrontier);
            if (pred.valid()) {
                lastPrediction = pred;
            }
            // Phase 6 — the policy decides the next scale (default ×10, or adaptive).
            double margin = cp.value() != null ? bound.margin(cp.x(), cp.value()) : 0.0;
            double xc = pred.valid() ? pred.computeFrontierX() : 0.0;
            long next = scalePolicy.nextScale(new NextScaleContext(x, margin, xc, task.scaleFactor()));
            if (next <= x) {          // overflow / non-increasing — stop cleanly
                break;
            }
            // Phase 2 — anticipate the stall: if the fitted cost says the next rung
            // would exceed the budget, stop now instead of grinding to a reactive stall.
            if (pred.valid()) {
                if (next > pred.computeFrontierX()) {
                    // Phase 3 — scarcity → innovation: pivot to a verifiable branch
                    // instead of stopping. Degrades to a plain stop if no pivot is wired.
                    if (branchPivot != null && branchPivot.isEnabled()) {
                        PivotOutcome pv = branchPivot.pivot(
                                new PivotRequest(task.spec(), next, task.checkpoints(), task.anchors(), pred, cps));
                        log.info("escalation predicted stall — {}; pivoting -> branch={} trust={} validated={}",
                                pred.basis(), pv.branch(), pv.trust(), pv.validated());
                        return EscalationOutcome.pivoted(cps, frontier, pred, pv);
                    }
                    log.info("escalation predicted stall — {}; next x={} exceeds compute frontier — stopping",
                            pred.basis(), next);
                    return EscalationOutcome.predictedStall(cps, frontier, pred);
                }
            }
            x = next;
        }
        return EscalationOutcome.exhausted(cps, frontier, lastPrediction);
    }

    private EscalationCheckpoint evaluateAt(long x, EscalationTask task,
                                            ScaleValueSource sieveSource, BoundPredicate bound) {
        Long value;
        String source;
        long elapsedMs;
        if (x <= ConjectureVerifier.MAX_N) {
            long t0 = System.nanoTime();
            value = sieveSource.valueAt(x);
            elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            source = "sieve";
        } else {
            DiscoveryTask dt = new DiscoveryTask(task.spec(), x, task.checkpoints(), task.anchors(),
                    task.cpuSeconds(), task.wallSeconds(), discovery.maxAttempts());
            DiscoveryOutcome out = discovery.discover(dt);
            value = out.accepted() ? out.targetValue() : null;
            elapsedMs = out.elapsedMs();
            source = "discovery";
        }
        if (value == null) {
            return new EscalationCheckpoint(x, null, EscalationVerdict.CANNOT_COMPUTE, source, elapsedMs);
        }
        return new EscalationCheckpoint(x, value, bound.classify(x, value), source, elapsedMs);
    }
}
