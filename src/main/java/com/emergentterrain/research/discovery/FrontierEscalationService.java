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
    private final MertensDiscoveryService discovery;

    public FrontierEscalationService(
            @Value("${app.research.discovery.frontierEscalationEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.frontierEscalationMaxRounds:8}") int maxRounds,
            MertensDiscoveryService discovery) {
        this.enabled = enabled;
        this.maxRounds = Math.max(1, maxRounds);
        this.discovery = discovery;
        log.info("FrontierEscalationService ready — frontierEscalation={} maxRounds={}",
                enabled ? "ON" : "OFF", this.maxRounds);
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
        long x = task.startScale();
        long frontier = 0;
        for (int round = 0; round < maxRounds; round++) {
            EscalationCheckpoint cp = evaluateAt(x, task, sieveSource, bound);
            cps.add(cp);
            log.info("escalation round {}/{} — x={} value={} verdict={} via {}",
                    round + 1, maxRounds, x, cp.value(), cp.verdict(), cp.source());
            switch (cp.verdict()) {
                case COUNTEREXAMPLE -> {
                    return EscalationOutcome.counterexample(cps, frontier);
                }
                case CANNOT_COMPUTE -> {
                    return EscalationOutcome.stalled(cps, frontier);
                }
                case HOLDS -> frontier = x;
            }
            long next = x * task.scaleFactor();
            if (next <= x) {          // overflow / non-increasing — stop cleanly
                break;
            }
            x = next;
        }
        return EscalationOutcome.exhausted(cps, frontier);
    }

    private EscalationCheckpoint evaluateAt(long x, EscalationTask task,
                                            ScaleValueSource sieveSource, BoundPredicate bound) {
        Long value;
        String source;
        if (x <= ConjectureVerifier.MAX_N) {
            value = sieveSource.valueAt(x);
            source = "sieve";
        } else {
            DiscoveryTask dt = new DiscoveryTask(task.spec(), x, task.checkpoints(), task.anchors(),
                    task.cpuSeconds(), task.wallSeconds(), discovery.maxAttempts());
            DiscoveryOutcome out = discovery.discover(dt);
            value = out.accepted() ? out.targetValue() : null;
            source = "discovery";
        }
        if (value == null) {
            return new EscalationCheckpoint(x, null, EscalationVerdict.CANNOT_COMPUTE, source);
        }
        return new EscalationCheckpoint(x, value, bound.classify(x, value), source);
    }
}
