package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 30 frontier-escalation — Tier 1 (sieve-range-only) frontier walk for the
 * Dirichlet divisor-sum bound {@code |D(x) − x·(ln x + 2γ−1)| < √x}, the divisor
 * analog of {@link MertensFrontierService}. It is the second domain wired into the
 * persist/recall corpus, proving the loop generalizes past Mertens.
 *
 * <p><b>Why a self-contained walk rather than {@link FrontierEscalationService}.</b>
 * The escalation engine routes any rung beyond {@code NT_MAX_N} (2,000,000) to
 * {@link MertensDiscoveryService} — Mertens-specific LLM discovery. Tier 1
 * deliberately stays inside the trusted sieve range, so it walks a fixed checkpoint
 * ladder against {@link SidecarDivisorSumValueSource} directly and never touches the
 * discovery path. Reach is capped at the sieve ceiling; pushing past it (LLM-discovered
 * {@code D(x)} methods) is Tier 2, which genericizes the escalation→discovery edge.
 *
 * <p>Every validated rung is persisted to {@link FrontierKnowledgeService} under bound
 * {@code "divisor_D"} (independent key from Mertens' {@code "mertens_M"}), so a paper on a
 * divisor-count topic can {@code recall_frontier_knowledge} an already-verified {@code D(x)}
 * corpus instead of re-deriving it. Pure sieve calls — spends no LLM tokens — so the
 * schedule gate ({@code divisorFrontierEnabled}) is safe to leave ON.
 */
@Service
public class DivisorFrontierService {

    private static final Logger log = LoggerFactory.getLogger(DivisorFrontierService.class);

    /** Stable identifier for the divisor-sum bound's accumulated knowledge. */
    static final String BOUND = "divisor_D";
    private static final String BOUND_LABEL =
            "|D(x) − x·(ln x + 2γ−1)| < sqrt(x) (the Dirichlet divisor error term)";
    /** Fixed ladder, all ≤ NT_MAX_N so the trusted oracle covers every rung. */
    private static final List<Long> CHECKPOINTS =
            List.of(1L, 10L, 1_000L, 100_000L, 1_000_000L, 2_000_000L);

    private final boolean scheduleEnabled;
    private final ScaleValueSource divisorSource;
    private FrontierKnowledgeService knowledge;

    @org.springframework.beans.factory.annotation.Autowired
    public DivisorFrontierService(
            @Value("${app.research.discovery.divisorFrontierEnabled:false}") boolean scheduleEnabled,
            SidecarDivisorSumValueSource divisorSource) {
        this.scheduleEnabled = scheduleEnabled;
        this.divisorSource = divisorSource;
    }

    /** Test seam — inject any value source directly. */
    DivisorFrontierService(boolean scheduleEnabled, ScaleValueSource divisorSource) {
        this.scheduleEnabled = scheduleEnabled;
        this.divisorSource = divisorSource;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledge(FrontierKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * Autonomous walk — gated by {@code divisorFrontierEnabled}. Unlike the Mertens
     * schedule this spends NO LLM tokens (sieve oracle only), so the gate is purely
     * about whether the divisor corpus is wanted. A {@code @Scheduled} fixed-delay fires
     * the first walk shortly after startup, then re-walks each interval until the
     * frontier converges (a fixed-ladder walk is repeatable, so it settles in
     * {@code convergenceThreshold} runs and then no-ops).
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.research.discovery.divisorFrontierScheduleMs:21600000}")
    public void scheduledDivisorRun() {
        if (!scheduleEnabled) {
            return;
        }
        if (knowledge != null && knowledge.isConverged(BOUND)) {
            log.info("scheduled divisor frontier run skipped — {} converged at frontier {}; no work",
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

    /**
     * Walk the fixed checkpoint ladder against the trusted sieve oracle, classifying each
     * rung with {@link BoundPredicate#DIVISOR_ERROR} and persisting it INCREMENTALLY (so a
     * mid-walk restart resumes from the latest reached x). Returns a rendered
     * {@link FrontierPaper}; the load-bearing output is the persisted {@code divisor_D}
     * corpus, which papers recall verbatim.
     */
    public FrontierPaper produce() {
        List<EscalationCheckpoint> cps = new ArrayList<>();
        long frontier = 0;
        for (long x : CHECKPOINTS) {
            long t0 = System.nanoTime();
            Long value = divisorSource.valueAt(x);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            EscalationCheckpoint cp;
            if (value == null) {
                // In-range the oracle always returns a value; a null here is a sieve/transport
                // gap — record it as an honest boundary, not silently dropped.
                cp = new EscalationCheckpoint(x, null, EscalationVerdict.CANNOT_COMPUTE,
                        "sieve", ms, null, "sieve_gap");
            } else {
                EscalationVerdict verdict = BoundPredicate.DIVISOR_ERROR.classify(x, value);
                cp = new EscalationCheckpoint(x, value, verdict, "sieve", ms);
                if (verdict == EscalationVerdict.HOLDS) {
                    frontier = Math.max(frontier, x);
                }
            }
            cps.add(cp);
            if (knowledge != null) {
                knowledge.recordCheckpoint(BOUND, cp);   // incremental persist (survives a restart)
            }
        }
        // All ladder rungs evaluated in-range — terminal "exhausted_rounds" so the narrative
        // reads "the bound HELD at every scale up to x=frontier" (the honest Tier-1 result).
        EscalationOutcome out = EscalationOutcome.exhausted(cps, frontier, null);
        String findings = FrontierFindingsRenderer.render(out, "D(x)", BOUND_LABEL);
        log.info("divisor frontier paper produced — terminal={} frontier={}",
                out.terminalState(), out.frontierReached());
        return new FrontierPaper(
                "Frontier walk of the Dirichlet divisor sum D(x) over the trusted sieve range",
                findings, out);
    }
}
