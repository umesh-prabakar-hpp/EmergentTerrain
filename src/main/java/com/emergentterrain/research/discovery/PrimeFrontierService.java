package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 frontier-escalation — Tier 3 (Path A) frontier run for the prime-counting
 * function {@code π(x) = #{p ≤ x : p prime}}, bound {@code |π(x) − li(x)| < √x·ln(x)/(8π)}
 * (the von Koch / Schoenfeld RH-equivalent bound). The prime analog of
 * {@link MertensFrontierService} / {@link DivisorFrontierService}: it runs the SAME generic
 * {@link FrontierEscalationService} — sieve oracle for x ≤ NT_MAX_N, LLM-discovered methods
 * beyond — and persists/recalls under the independent bound key {@code "prime_pi"}.
 *
 * <p>It threads {@code quantity = "prime"} through the {@link DiscoveryTask} to the sidecar
 * {@code /discover/validate}, which selects the trusted oracle ({@code nt_prime_indicator}
 * cumulative → π(x)) instead of Mertens/divisor. So a prime candidate is moat-validated
 * against the real π(x) on the overlap checkpoints — the loop generalizes past the sieve for
 * any bound with a sidecar oracle. Escalating this bound is testing the Riemann Hypothesis
 * numerically: it keeps HOLDing across every reachable scale and the loop stalls before the
 * wall (see {@link BoundPredicate#PRIME_PI}).
 *
 * <p>Gated by {@code primeFrontierScheduleEnabled} (the schedule/spend gate, separate from the
 * escalation-logic flag). Beyond the sieve it spends LLM tokens (the discovery rungs), exactly
 * like the Mertens/divisor schedules, so it is OFF by default and the long interval bounds the
 * cost.
 */
@Service
public class PrimeFrontierService {

    private static final Logger log = LoggerFactory.getLogger(PrimeFrontierService.class);

    /** Stable identifier for the prime-counting bound's accumulated knowledge. */
    static final String BOUND = "prime_pi";
    /** The quantity key the sidecar oracle switch understands (see handle_discover_validate). */
    private static final String QUANTITY = "prime";
    private static final String SPEC =
            "P(x) = the number of primes p <= x (the prime-counting function pi(x))";
    private static final String BOUND_LABEL =
            "|pi(x) - li(x)| < sqrt(x)*ln(x)/(8*pi) (the von Koch / Schoenfeld RH bound)";
    /** Overlap checkpoints (1..NT_MAX_N) the discovery moat validates a candidate against. */
    private static final List<Long> CHECKPOINTS =
            List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    /** No beyond-range external anchor wired here; the overlap checkpoints carry the moat. */
    private static final Map<Long, Long> ANCHORS = Map.of();

    private final long startScale;
    private final boolean scheduleEnabled;
    private final int cpuSeconds;
    private final int wallSeconds;
    private final FrontierEscalationService escalation;
    private final ScaleValueSource sieveSource;

    /** Back-compatible constructor (default 60s/80s sandbox budget) for tests/call sites. */
    PrimeFrontierService(long startScale, boolean scheduleEnabled,
                         FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this(startScale, scheduleEnabled, 60, 80, escalation, sieveSource);
    }

    /** Canonical initializer (interface-typed source so tests can pass a fake). */
    private PrimeFrontierService(long startScale, boolean scheduleEnabled, int cpuSeconds, int wallSeconds,
                                 FrontierEscalationService escalation, ScaleValueSource sieveSource) {
        this.startScale = startScale < 1 ? 2_000_000 : startScale;
        this.scheduleEnabled = scheduleEnabled;
        this.cpuSeconds = Math.max(1, cpuSeconds);
        this.wallSeconds = Math.max(this.cpuSeconds + 1, wallSeconds);
        this.escalation = escalation;
        this.sieveSource = sieveSource;
    }

    // Spring path — inject the CONCRETE prime source so this isn't ambiguous against the
    // Mertens/divisor ScaleValueSource beans (each frontier service binds its own oracle by type).
    @org.springframework.beans.factory.annotation.Autowired
    public PrimeFrontierService(
            @Value("${app.research.discovery.primeFrontierStartScale:2000000}") long startScale,
            @Value("${app.research.discovery.primeFrontierScheduleEnabled:false}") boolean scheduleEnabled,
            @Value("${app.research.discovery.primeFrontierCpuSeconds:60}") int cpuSeconds,
            @Value("${app.research.discovery.primeFrontierWallSeconds:80}") int wallSeconds,
            FrontierEscalationService escalation,
            SidecarPrimeCountValueSource sieveSource) {
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
     * Autonomous frontier run — gated by {@code primeFrontierScheduleEnabled}. Spends LLM tokens
     * on the discovery rungs beyond the sieve (like the Mertens/divisor schedules), so it stays
     * OFF until the operator opts in. Run-until-converged: stops re-spending once stable.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.research.discovery.primeFrontierScheduleMs:21600000}")
    public void scheduledPrimeRun() {
        if (!scheduleEnabled) {
            return;
        }
        if (knowledge != null && knowledge.isConverged(BOUND)) {
            log.info("scheduled prime frontier run skipped — {} converged at frontier {}; no spend",
                    BOUND, knowledge.frontier(BOUND));
            return;
        }
        FrontierPaper paper = produce();
        if (knowledge != null) {
            FrontierKnowledgeService.RunRecord rr = knowledge.record(BOUND, paper.outcome());
            log.info("scheduled prime frontier run — {} | terminal={} frontier={} kept={} converged={}",
                    paper.title(), paper.outcome().terminalState(), rr.frontier(), rr.recordsKept(), rr.converged());
        } else {
            log.info("scheduled prime frontier run — {} | terminal={} frontier={}",
                    paper.title(), paper.outcome().terminalState(), paper.outcome().frontierReached());
        }
    }

    public FrontierPaper produce() {
        // Pre-flight — a run that escalates past NT_MAX_N needs the discovery endpoint. Skip the
        // WHOLE run (no escalation, no per-rung LLM scale calls, no discovery) if it's gated/down.
        if (endpointHealth != null && !endpointHealth.available()) {
            log.warn("prime frontier run skipped — discovery endpoint unavailable (set ET_DISCOVER_EXEC=1); no tokens spent");
            return new FrontierPaper(
                    "Frontier escalation of the prime-counting function pi(x)",
                    "_Frontier run skipped: the discovery endpoint `/discover/validate` is unavailable "
                            + "(403/unreachable; set `ET_DISCOVER_EXEC=1` on the sidecar). No tokens were spent._",
                    EscalationOutcome.disabled());
        }
        // §10/B2 — RESUME from the persisted frontier so a run pushes PAST where the last reached.
        long resumeFrom = knowledge == null ? 0 : knowledge.frontier(BOUND);
        long effectiveStart = Math.max(startScale, resumeFrom);
        if (effectiveStart > startScale) {
            log.info("prime frontier run resuming from accumulated frontier x={} (configured start {})",
                    effectiveStart, startScale);
        }
        EscalationTask task = new EscalationTask(SPEC, effectiveStart, 10L,
                CHECKPOINTS, ANCHORS, cpuSeconds, wallSeconds, QUANTITY);
        List<FrontierEvent> priorEvents = knowledge == null
                ? List.of() : knowledge.recall(BOUND).events();
        java.util.function.Consumer<EscalationCheckpoint> sink =
                knowledge == null ? null : cp -> knowledge.recordCheckpoint(BOUND, cp);
        EscalationOutcome out = escalation.escalate(task, sieveSource, BoundPredicate.PRIME_PI, priorEvents, sink);
        String findings = FrontierFindingsRenderer.render(out, "pi(x)", BOUND_LABEL);
        log.info("prime frontier paper produced — terminal={} frontier={} pivot={}",
                out.terminalState(), out.frontierReached(), out.pivot() == null ? "none" : out.pivot().branch());
        return new FrontierPaper(
                "Frontier escalation of the prime-counting function pi(x)", findings, out);
    }
}
