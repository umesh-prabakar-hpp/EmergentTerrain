package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 frontier-escalation Phase 4 — composes the Mertens frontier run and
 * renders it into a {@link FrontierPaper}: builds the {@link EscalationTask}
 * (the cumulative-Mertens spec, the standard overlap checkpoints + OEIS anchor,
 * the start scale), runs {@link FrontierEscalationService#escalate} with the
 * sidecar value source and {@link BoundPredicate#MERTENS}, and hands the outcome
 * to {@link FrontierFindingsRenderer}.
 *
 * <p>Gated by {@code frontierEscalationEnabled} (via the escalation service):
 * when off, {@code escalate} returns a disabled outcome and {@link #produce}
 * yields a "disabled" paper. The tool/cycle that calls {@link #produce} is the
 * flip+watch step.
 */
@Service
public class MertensFrontierService {

    private static final Logger log = LoggerFactory.getLogger(MertensFrontierService.class);

    private static final String SPEC =
            "M(x) = sum of the Möbius function mu(n) for n = 1..x (the cumulative Mertens function)";
    private static final String BOUND_LABEL = "|M(x)| < sqrt(x) (the Mertens conjecture)";
    private static final List<Long> CHECKPOINTS =
            List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    private static final Map<Long, Long> ANCHORS = Map.of(10_000_000L, 1037L);

    private final long startScale;
    private final FrontierEscalationService escalation;
    private final ScaleValueSource sieveSource;

    public MertensFrontierService(
            @Value("${app.research.discovery.frontierStartScale:2000000}") long startScale,
            FrontierEscalationService escalation,
            ScaleValueSource sieveSource) {
        this.startScale = startScale < 1 ? 2_000_000 : startScale;
        this.escalation = escalation;
        this.sieveSource = sieveSource;
    }

    public FrontierPaper produce() {
        EscalationTask task = new EscalationTask(SPEC, startScale, 10L, CHECKPOINTS, ANCHORS, 20, 28);
        EscalationOutcome out = escalation.escalate(task, sieveSource, BoundPredicate.MERTENS);
        String findings = FrontierFindingsRenderer.render(out, "M(x)", BOUND_LABEL);
        log.info("frontier paper produced — terminal={} frontier={} pivot={}",
                out.terminalState(), out.frontierReached(), out.pivot() == null ? "none" : out.pivot().branch());
        return new FrontierPaper(
                "Frontier escalation of the Mertens bound |M(x)| < sqrt(x)", findings, out);
    }
}
