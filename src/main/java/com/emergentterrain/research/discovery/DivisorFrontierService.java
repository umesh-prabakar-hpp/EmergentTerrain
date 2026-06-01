package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 frontier-escalation — Tier 2 frontier run for the Dirichlet divisor sum
 * {@code D(x) = Σ_{n≤x} d(n)}, bound {@code |D(x) − x·(ln x + 2γ−1)| < √x}. The divisor
 * analog of {@link MertensFrontierService}: it runs the SAME generic
 * {@link FrontierEscalationService} — sieve oracle for x ≤ NT_MAX_N, LLM-discovered
 * methods beyond — and persists/recalls under the independent bound key {@code "divisor_D"}.
 *
 * <p>Tier 2 closed the one Mertens coupling that Tier 1 had to route around: the escalation
 * engine now threads {@code quantity} (here {@code "divisor"}) through the {@link DiscoveryTask}
 * to the sidecar {@code /discover/validate}, which selects the trusted oracle
 * ({@code nt_divisor_count} cumulative → D(x)) instead of always Mertens. So a divisor candidate
 * is moat-validated against the real D(x) on the overlap checkpoints — the loop generalizes
 * past the sieve for any bound with a sidecar oracle, not just Mertens.
 *
 * <p>Gated by {@code divisorFrontierEnabled} (the schedule/spend gate, separate from the
 * escalation-logic flag). Beyond the sieve it spends LLM tokens (the discovery rungs), exactly
 * like the Mertens schedule, so it is OFF by default and the long interval bounds the cost.
 */
@Service
public class DivisorFrontierService {

    private static final Logger log = LoggerFactory.getLogger(DivisorFrontierService.class);

    /** Stable identifier for the divisor-sum bound's accumulated knowledge. */
    static final String BOUND = "divisor_D";
    /** The quantity key the sidecar oracle switch understands (see handle_discover_validate). */
    private static final String QUANTITY = "divisor";
    private static final String SPEC =
            "D(x) = sum of the number-of-divisors function d(n) for n = 1..x (the Dirichlet divisor summatory function)";
    private static final String BOUND_LABEL =
            "|D(x) − x·(ln x + 2γ−1)| < sqrt(x) (the Dirichlet divisor error term)";
    /** Overlap checkpoints (1..NT_MAX_N) the discovery moat validates a candidate against. */
    private static final List<Long> CHECKPOINTS =
            List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    /** No beyond-range external anchor wired yet; the overlap checkpoints carry the moat. */
    private static final Map<Long, Long> ANCHORS = Map.of();

    private final long startScale;
    private final boolean scheduleEnabled;
    private final int cpuSeconds;
    private final int wallSeconds;
    private final FrontierEscalationService escalation;
    private final ScaleValueSource sieveSource;

    /** Back-compatible constructor (default 60s/80s sandbox budget) for tests/call sites. */
    DivisorFrontierService(long startScale, boolean scheduleEnabled,
                           FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this(startScale, scheduleEnabled, 60, 80, escalation, sieveSource);
    }

    /** Canonical initializer (interface-typed source so tests can pass a fake). */
    private DivisorFrontierService(long startScale, boolean scheduleEnabled, int cpuSeconds, int wallSeconds,
                                   FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this.startScale = startScale < 1 ? 2_000_000 : startScale;
        this.scheduleEnabled = scheduleEnabled;
        this.cpuSeconds = Math.max(1, cpuSeconds);
        this.wallSeconds = Math.max(this.cpuSeconds + 1, wallSeconds);
        this.escalation = escalation;
        this.sieveSource = sieveSource;
    }

    // Spring path — inject the CONCRETE divisor source so this isn't ambiguous against the
    // Mertens ScaleValueSource bean (each frontier service binds its own oracle by concrete type).
    @org.springframework.beans.factory.annotation.Autowired
    public DivisorFrontierService(
            @Value("${app.research.discovery.divisorFrontierStartScale:2000000}") long startScale,
            @Value("${app.research.discovery.divisorFrontierEnabled:false}") boolean scheduleEnabled,
            @Value("${app.research.discovery.divisorFrontierCpuSeconds:60}") int cpuSeconds,
            @Value("${app.research.discovery.divisorFrontierWallSeconds:80}") int wallSeconds,
            FrontierEscalationService escalation,
            SidecarDivisorSumValueSource sieveSource) {
        this(startScale, scheduleEnabled, cpuSeconds, wallSeconds, escalation, (ScaleValueSource) sieveSource);
    }

    private FrontierKnowledgeService knowledge;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledge(FrontierKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    private DiscoveryEndpointHealth endpointHealth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEndpointHealth(DiscoveryEndpointHealth endpointHealth) {
        this.endpointHealth = endpointHealth;
    }

    /**
     * Autonomous frontier run — gated by {@code divisorFrontierEnabled}. Spends LLM tokens on
     * the discovery rungs beyond the sieve (like the Mertens schedule), so it stays OFF until the
     * operator opts in. Run-until-converged: stops re-spending once the frontier stabilizes.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.research.discovery.divisorFrontierScheduleMs:21600000}")
    public void scheduledDivisorRun() {
        if (!scheduleEnabled) {
            return;
        }
        if (knowledge != null && knowledge.isConverged(BOUND)) {
            log.info("scheduled divisor frontier run skipped — {} converged at frontier {}; no spend",
                    BOUND, knowledge.frontier(BOUND));
            return;
        }
        FrontierPaper paper = produce();
        if (knowledge != null) {
            FrontierKnowledgeService.RunRecord rr = knowledge.record(BOUND, paper.outcome());
            log.info("scheduled divisor frontier run — {} | terminal={} frontier={} kept={} converged={}",
                    paper.title(), paper.outcome().terminalState(), rr.frontier(), rr.recordsKept(), rr.converged());
        } else {
            log.info("scheduled divisor frontier run — {} | terminal={} frontier={}",
                    paper.title(), paper.outcome().terminalState(), paper.outcome().frontierReached());
        }
    }

    public FrontierPaper produce() {
        // Pre-flight — a run that escalates past NT_MAX_N needs the discovery endpoint. Skip the
        // WHOLE run (no escalation, no per-rung LLM scale calls, no discovery) if it's gated/down.
        if (endpointHealth != null && !endpointHealth.available()) {
            log.warn("divisor frontier run skipped — discovery endpoint unavailable (set ET_DISCOVER_EXEC=1); no tokens spent");
            return new FrontierPaper(
                    "Frontier escalation of the Dirichlet divisor sum D(x)",
                    "_Frontier run skipped: the discovery endpoint `/discover/validate` is unavailable "
                            + "(403/unreachable; set `ET_DISCOVER_EXEC=1` on the sidecar). No tokens were spent._",
                    EscalationOutcome.disabled());
        }
        // §10/B2 — RESUME from the persisted frontier so a run pushes PAST where the last reached.
        long resumeFrom = knowledge == null ? 0 : knowledge.frontier(BOUND);
        long effectiveStart = Math.max(startScale, resumeFrom);
        if (effectiveStart > startScale) {
            log.info("divisor frontier run resuming from accumulated frontier x={} (configured start {})",
                    effectiveStart, startScale);
        }
        EscalationTask task = new EscalationTask(SPEC, effectiveStart, 10L,
                CHECKPOINTS, ANCHORS, cpuSeconds, wallSeconds, QUANTITY);
        List<FrontierEvent> priorEvents = knowledge == null
                ? List.of() : knowledge.recall(BOUND).events();
        java.util.function.Consumer<EscalationCheckpoint> sink =
                knowledge == null ? null : cp -> knowledge.recordCheckpoint(BOUND, cp);
        EscalationOutcome out = escalation.escalate(task, sieveSource, BoundPredicate.DIVISOR_ERROR, priorEvents, sink);
        String findings = FrontierFindingsRenderer.render(out, "D(x)", BOUND_LABEL);
        log.info("divisor frontier paper produced — terminal={} frontier={} pivot={}",
                out.terminalState(), out.frontierReached(), out.pivot() == null ? "none" : out.pivot().branch());
        return new FrontierPaper(
                "Frontier escalation of the Dirichlet divisor sum D(x)", findings, out);
    }
}
