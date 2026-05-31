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
    private final boolean scheduleEnabled;
    private final FrontierEscalationService escalation;
    private final ScaleValueSource sieveSource;

    public MertensFrontierService(
            @Value("${app.research.discovery.frontierStartScale:2000000}") long startScale,
            @Value("${app.research.discovery.frontierScheduleEnabled:false}") boolean scheduleEnabled,
            FrontierEscalationService escalation,
            ScaleValueSource sieveSource) {
        this.startScale = startScale < 1 ? 2_000_000 : startScale;
        this.scheduleEnabled = scheduleEnabled;
        this.escalation = escalation;
        this.sieveSource = sieveSource;
    }

    /**
     * Phase 5 — autonomous frontier run. Gated by {@code frontierScheduleEnabled}
     * (default OFF, separate from the escalation-logic flag) because each run
     * spends real LLM tokens across the discovery rungs; the long default interval
     * (6 h) bounds that. No-op until the operator opts in. Watch the log for
     * "frontier paper produced".
     */
    /** Stable identifier for the Mertens bound's accumulated knowledge. */
    private static final String BOUND = "mertens_M";

    private FrontierKnowledgeService knowledge;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledge(FrontierKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.research.discovery.frontierScheduleMs:21600000}")
    public void scheduledFrontierRun() {
        if (!scheduleEnabled) {
            return;
        }
        // Run-until-converged — stop re-spending once the frontier + pivot stabilize.
        if (knowledge != null && knowledge.isConverged(BOUND)) {
            log.info("scheduled frontier run skipped — {} converged at frontier {}; no spend (re-engages on a new trigger)",
                    BOUND, knowledge.frontier(BOUND));
            return;
        }
        FrontierPaper paper = produce();
        if (knowledge != null) {
            FrontierKnowledgeService.RunRecord rr = knowledge.record(BOUND, paper.outcome());
            log.info("scheduled frontier run — {} | terminal={} frontier={} kept={} converged={}",
                    paper.title(), paper.outcome().terminalState(), rr.frontier(), rr.recordsKept(), rr.converged());
        } else {
            log.info("scheduled frontier run — {} | terminal={} frontier={}",
                    paper.title(), paper.outcome().terminalState(), paper.outcome().frontierReached());
        }
    }

    private DiscoveryEndpointHealth endpointHealth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEndpointHealth(DiscoveryEndpointHealth endpointHealth) {
        this.endpointHealth = endpointHealth;
    }

    public FrontierPaper produce() {
        // Pre-flight — a Mertens frontier run escalates past NT_MAX_N, so it needs discovery.
        // Skip the WHOLE run (no escalation, no per-rung LLM scale calls, no discovery) if the
        // endpoint is gated/down — zero tokens spent.
        if (endpointHealth != null && !endpointHealth.available()) {
            log.warn("frontier run skipped — discovery endpoint unavailable (set ET_DISCOVER_EXEC=1); no tokens spent");
            return new FrontierPaper(
                    "Frontier escalation of the Mertens bound |M(x)| < sqrt(x)",
                    "_Frontier run skipped: the discovery endpoint `/discover/validate` is unavailable "
                            + "(403/unreachable; set `ET_DISCOVER_EXEC=1` on the sidecar). No tokens were spent._",
                    EscalationOutcome.disabled());
        }
        EscalationTask task = new EscalationTask(SPEC, startScale, 10L, CHECKPOINTS, ANCHORS, 20, 28);
        EscalationOutcome out = escalation.escalate(task, sieveSource, BoundPredicate.MERTENS);
        String findings = FrontierFindingsRenderer.render(out, "M(x)", BOUND_LABEL);
        log.info("frontier paper produced — terminal={} frontier={} pivot={}",
                out.terminalState(), out.frontierReached(), out.pivot() == null ? "none" : out.pivot().branch());
        return new FrontierPaper(
                "Frontier escalation of the Mertens bound |M(x)| < sqrt(x)", findings, out);
    }
}
