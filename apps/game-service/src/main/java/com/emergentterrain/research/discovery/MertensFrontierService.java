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
    // §10 — the budget/freedom framing ("strict budget, large x, be inventive, validator checks")
    // is NO LONGER appended here: it moved to the shared LlmCandidateGenerator CONTRACT so it
    // applies to EVERY discovery domain (Mertens, proxy, future bounds), not just this spec. The
    // LLM is the creative engine; the moat is the verifier; the rules of the game are domain-neutral.
    private static final String BOUND_LABEL = "|M(x)| < sqrt(x) (the Mertens conjecture)";
    private static final List<Long> CHECKPOINTS =
            List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    private static final Map<Long, Long> ANCHORS = Map.of(10_000_000L, 1037L);

    private final long startScale;
    private final boolean scheduleEnabled;
    private final int cpuSeconds;
    private final int wallSeconds;
    private final FrontierEscalationService escalation;
    private final ScaleValueSource sieveSource;

    /** Back-compatible constructor (default 60s/80s sandbox budget) for tests/call sites. */
    MertensFrontierService(long startScale, boolean scheduleEnabled,
                           FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this(startScale, scheduleEnabled, 60, 80, escalation, sieveSource);
    }

    /** Canonical initializer (interface-typed source so tests can pass a fake). */
    private MertensFrontierService(long startScale, boolean scheduleEnabled, int cpuSeconds, int wallSeconds,
                                   FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this.startScale = startScale < 1 ? 2_000_000 : startScale;
        this.scheduleEnabled = scheduleEnabled;
        // per-candidate sandbox budget — bigger CPU reaches bigger x; wall stays above CPU
        // for JIT/startup headroom (see frontier-escalation-loop.md §10).
        this.cpuSeconds = Math.max(1, cpuSeconds);
        this.wallSeconds = Math.max(this.cpuSeconds + 1, wallSeconds);
        this.escalation = escalation;
        this.sieveSource = sieveSource;
    }

    // Spring path — inject the CONCRETE Mertens source. With a second ScaleValueSource bean
    // now present (SidecarDivisorSumValueSource), an interface-typed param would be ambiguous.
    @org.springframework.beans.factory.annotation.Autowired
    public MertensFrontierService(
            @Value("${app.research.discovery.frontierStartScale:2000000}") long startScale,
            @Value("${app.research.discovery.mertensFrontierScheduleEnabled:false}") boolean scheduleEnabled,
            @Value("${app.research.discovery.frontierCpuSeconds:60}") int cpuSeconds,
            @Value("${app.research.discovery.frontierWallSeconds:80}") int wallSeconds,
            FrontierEscalationService escalation,
            SidecarMertensValueSource sieveSource) {
        this(startScale, scheduleEnabled, cpuSeconds, wallSeconds, escalation, (ScaleValueSource) sieveSource);
    }

    /**
     * Phase 5 — autonomous frontier run. Gated by {@code mertensFrontierScheduleEnabled}
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
        // §10/B2 — RESUME from the persisted frontier instead of re-walking from the sieve
        // each run: start at the larger of the configured start scale and the accumulated
        // frontier, so a run pushes PAST where the last one reached (and convergence reflects
        // real new ground, not a re-derivation). Round 1 re-validates the frontier (a cheap
        // sanity check that re-banks the method); the rest is new territory.
        long resumeFrom = knowledge == null ? 0 : knowledge.frontier(BOUND);
        long effectiveStart = Math.max(startScale, resumeFrom);
        if (effectiveStart > startScale) {
            log.info("frontier run resuming from accumulated frontier x={} (configured start {})",
                    effectiveStart, startScale);
        }
        EscalationTask task = new EscalationTask(SPEC, effectiveStart, 10L,
                CHECKPOINTS, ANCHORS, cpuSeconds, wallSeconds);
        // §10.3/B2 — seed the scale policy with persisted boundaries from prior runs so a
        // fresh run starts informed (inform, not suppress). Empty if no knowledge wired/recorded.
        java.util.List<FrontierEvent> priorEvents = knowledge == null
                ? java.util.List.of() : knowledge.recall(BOUND).events();
        // Incremental persistence — each validated rung is written immediately, so a restart
        // mid-run resumes from the latest reached x (not the last completed run's frontier).
        java.util.function.Consumer<EscalationCheckpoint> sink =
                knowledge == null ? null : cp -> knowledge.recordCheckpoint(BOUND, cp);
        EscalationOutcome out = escalation.escalate(task, sieveSource, BoundPredicate.MERTENS, priorEvents, sink);
        String findings = FrontierFindingsRenderer.render(out, "M(x)", BOUND_LABEL);
        log.info("frontier paper produced — terminal={} frontier={} pivot={}",
                out.terminalState(), out.frontierReached(), out.pivot() == null ? "none" : out.pivot().branch());
        return new FrontierPaper(
                "Frontier escalation of the Mertens bound |M(x)| < sqrt(x)", findings, out);
    }
}
