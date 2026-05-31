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
    private final int maxConsecutiveFailures;
    private final MertensDiscoveryService discovery;

    /** Back-compatible constructor (maxConsecutiveFailures defaults to 1 — old terminate-on-first-failure). */
    FrontierEscalationService(boolean enabled, int maxRounds, double stallBudgetMs, double scienceFrontier,
                              MertensDiscoveryService discovery) {
        this(enabled, maxRounds, stallBudgetMs, scienceFrontier, 1, discovery);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FrontierEscalationService(
            @Value("${app.research.discovery.frontierEscalationEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.frontierEscalationMaxRounds:8}") int maxRounds,
            @Value("${app.research.discovery.frontierStallBudgetMs:30000}") double stallBudgetMs,
            @Value("${app.research.discovery.frontierScienceFrontier:0}") double scienceFrontier,
            @Value("${app.research.discovery.frontierMaxConsecutiveFailures:3}") int maxConsecutiveFailures,
            MertensDiscoveryService discovery) {
        this.enabled = enabled;
        this.maxRounds = Math.max(1, maxRounds);
        this.stallBudgetMs = stallBudgetMs;
        this.scienceFrontier = scienceFrontier;
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.discovery = discovery;
        log.info("FrontierEscalationService ready — frontierEscalation={} maxRounds={} stallBudgetMs={} scienceFrontier={} maxConsecutiveFailures={}",
                enabled ? "ON" : "OFF", this.maxRounds, stallBudgetMs, scienceFrontier, this.maxConsecutiveFailures);
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
     * Phase 6 — the step policy, selected by {@code frontierScalePolicy}:
     * {@code default} (fixed ×10), {@code adaptive} (aggressive margin-driven), or
     * {@code llm} (model-decided, falls back to adaptive if its bean is absent).
     * Chosen in {@link #selectScalePolicy} after autowiring; tests call
     * {@link #setScalePolicy} directly.
     */
    private NextScalePolicy scalePolicy = new DefaultNextScalePolicy();

    @Value("${app.research.discovery.frontierScalePolicy:default}")
    private String scalePolicyName;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LlmNextScalePolicy llmNextScalePolicy;

    @jakarta.annotation.PostConstruct
    void selectScalePolicy() {
        this.scalePolicy = switch (scalePolicyName == null ? "default" : scalePolicyName.toLowerCase()) {
            case "llm" -> llmNextScalePolicy != null ? llmNextScalePolicy : new AdaptiveNextScalePolicy();
            case "adaptive" -> new AdaptiveNextScalePolicy();
            default -> new DefaultNextScalePolicy();
        };
        log.info("FrontierEscalationService scale policy — {} ({})",
                scalePolicyName, scalePolicy.getClass().getSimpleName());
    }

    /** Test seam — inject a policy directly. */
    void setScalePolicy(NextScalePolicy scalePolicy) {
        this.scalePolicy = scalePolicy;
    }

    /**
     * Phase 6 — when ON, a counterexample is *localized* by bisection (the bracket
     * {@code (largest-held-below, first-failed]} is narrowed) instead of stopping at
     * the first failure. Default OFF (the first counterexample is the headline
     * result). Bisection rungs route ≤NT_MAX_N to the sieve, beyond to discovery.
     */
    @Value("${app.research.discovery.frontierLocalizeEnabled:false}")
    private boolean localizeEnabled = false;
    private static final int LOCALIZE_MAX_ROUNDS = 30;
    private static final double LOCALIZE_TOLERANCE = 1.1;   // stop when hi/lo ≤ this (within 10%)

    void setLocalizeEnabled(boolean localizeEnabled) {
        this.localizeEnabled = localizeEnabled;
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
        return escalate(task, sieveSource, bound, List.of());
    }

    /**
     * §10.3/B2 — overload that seeds the scale policy with {@code priorEvents}: the
     * persisted failure/boundary events from previous runs, so a fresh run starts
     * INFORMED of which scales were unreachable (the LLM may still retry them — inform,
     * not suppress). Fetched by the caller from the knowledge store.
     */
    public EscalationOutcome escalate(EscalationTask task, ScaleValueSource sieveSource, BoundPredicate bound,
                                      List<FrontierEvent> priorEvents) {
        if (!enabled) {
            return EscalationOutcome.disabled();
        }
        List<FrontierEvent> priors = priorEvents == null ? List.of() : List.copyOf(priorEvents);
        List<EscalationCheckpoint> cps = new ArrayList<>();
        List<CostObservation> costs = new ArrayList<>();   // discovery rungs only — fed to the predictor
        java.util.Set<Long> visited = new java.util.HashSet<>();   // cycle guard for bidirectional steps
        StallPrediction lastPrediction = null;
        long x = task.startScale();
        visited.add(x);
        long frontier = 0;
        // §10.1 — a discovery failure is FEEDBACK, not a terminal: the loop keeps going and
        // re-consults the scale policy with the failure, so the LLM can pick a reachable x.
        // After maxConsecutiveFailures with no progress it routes to the stall→pivot path.
        int consecutiveFailures = 0;
        long lastFailedX = 0;
        String lastFailureReason = null;
        for (int round = 0; round < maxRounds; round++) {
            EscalationCheckpoint cp = evaluateAt(x, task, sieveSource, bound);
            cps.add(cp);
            log.info("escalation round {}/{} — x={} value={} verdict={} via {} ({}ms)",
                    round + 1, maxRounds, x, cp.value(), cp.verdict(), cp.source(), cp.elapsedMs());
            switch (cp.verdict()) {
                case COUNTEREXAMPLE -> {
                    long lo = localizeEnabled ? largestHeldBelow(cps, x) : 0;
                    if (lo <= 0) {
                        return EscalationOutcome.counterexample(cps, frontier);   // no held lower bound to bracket from
                    }
                    long[] bracket = localize(lo, x, task, sieveSource, bound, cps, visited);
                    return EscalationOutcome.counterexampleLocalized(cps, bracket[0], bracket[1]);
                }
                case CANNOT_COMPUTE -> {
                    consecutiveFailures++;
                    lastFailedX = x;
                    lastFailureReason = cp.failureStatus() != null ? cp.failureStatus() : "cannot_compute";
                    log.info("escalation — x={} did not compute ({}); feeding back to the scale policy "
                            + "(consecutive {}/{})", x, lastFailureReason, consecutiveFailures, maxConsecutiveFailures);
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        // the LLM has had its chances to self-correct → degrade to pivot or stall
                        StallPrediction pred = StallPredictor.predict(costs, stallBudgetMs, scienceFrontier);
                        if (branchPivot != null && branchPivot.isEnabled()) {
                            PivotOutcome pv = branchPivot.pivot(
                                    new PivotRequest(task.spec(), x, task.checkpoints(), task.anchors(), pred, cps));
                            log.info("escalation — {} consecutive discovery failures; pivoting -> branch={} trust={} validated={}",
                                    consecutiveFailures, pv.branch(), pv.trust(), pv.validated());
                            return EscalationOutcome.pivoted(cps, frontier, pred, pv);
                        }
                        log.info("escalation — {} consecutive discovery failures and no pivot wired; stalling at frontier={}",
                                consecutiveFailures, frontier);
                        return EscalationOutcome.stalled(cps, frontier);
                    }
                    // fall through to choose the next (informed) scale — don't return
                }
                case HOLDS -> {
                    frontier = Math.max(frontier, x);   // max held — a backward step can't lower it
                    consecutiveFailures = 0;            // progress resets the failure streak
                }
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
            // Phase 6 — the policy decides the next scale (default ×10 / adaptive / llm).
            // The step may go forward (escalate) OR backward (densify/localize); the
            // loop stops on no-progress or a revisit (cycle/converged), not on next<=x.
            // §10.2 — the last failure (x + reason) is handed to the policy so the LLM
            // adjusts; we INFORM, we do NOT clamp the step.
            double margin = cp.value() != null ? bound.margin(cp.x(), cp.value()) : 0.0;
            double xc = pred.valid() ? pred.computeFrontierX() : 0.0;
            long next = scalePolicy.nextScale(new NextScaleContext(
                    x, margin, xc, task.scaleFactor(), cps, lastFailedX, lastFailureReason, priors));
            if (next < 1 || next == x || !visited.add(next)) {
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

    /** Largest scale that HELD and is strictly below {@code x} — the lower bracket for localization. */
    private static long largestHeldBelow(List<EscalationCheckpoint> cps, long x) {
        long lo = 0;
        for (EscalationCheckpoint c : cps) {
            if (c.verdict() == EscalationVerdict.HOLDS && c.x() < x && c.x() > lo) {
                lo = c.x();
            }
        }
        return lo;
    }

    /**
     * Bisect {@code (lo, hi]} to localize where the bound stops holding: probe the
     * geometric midpoint, move {@code lo} up on HOLDS or {@code hi} down on a
     * counterexample, until the bracket is tight ({@code hi/lo ≤ tolerance}),
     * converges, or can't be computed. Appends each probe to {@code cps}. Returns
     * {@code [lo, hi]}.
     */
    private long[] localize(long lo, long hi, EscalationTask task, ScaleValueSource sieveSource,
                            BoundPredicate bound, List<EscalationCheckpoint> cps, java.util.Set<Long> visited) {
        for (int i = 0; i < LOCALIZE_MAX_ROUNDS && (double) hi / lo > LOCALIZE_TOLERANCE; i++) {
            long mid = Math.round(Math.exp((Math.log((double) lo) + Math.log((double) hi)) / 2.0));
            if (mid <= lo || mid >= hi || !visited.add(mid)) {
                break;   // converged / cycle
            }
            EscalationCheckpoint cp = evaluateAt(mid, task, sieveSource, bound);
            cps.add(cp);
            log.info("localize {} — bracket ({}, {}] probe x={} -> {}", i + 1, lo, hi, mid, cp.verdict());
            switch (cp.verdict()) {
                case HOLDS -> lo = mid;
                case COUNTEREXAMPLE -> hi = mid;
                case CANNOT_COMPUTE -> { return new long[]{lo, hi}; }   // can't probe — stop with current bracket
            }
        }
        return new long[]{lo, hi};
    }

    private EscalationCheckpoint evaluateAt(long x, EscalationTask task,
                                            ScaleValueSource sieveSource, BoundPredicate bound) {
        Long value;
        String source;
        long elapsedMs;
        String methodCode = null;
        String failureStatus = null;
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
            if (out.accepted()) {
                methodCode = out.code();           // the validated method (for the library)
            } else {
                methodCode = out.failedCode();     // the last rejected candidate (for the boundary event)
                failureStatus = out.failureStatus();
            }
        }
        if (value == null) {
            return new EscalationCheckpoint(x, null, EscalationVerdict.CANNOT_COMPUTE, source, elapsedMs,
                    methodCode, failureStatus);
        }
        return new EscalationCheckpoint(x, value, bound.classify(x, value), source, elapsedMs, methodCode);
    }
}
