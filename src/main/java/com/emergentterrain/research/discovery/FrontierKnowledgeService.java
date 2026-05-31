package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.FrontierKnowledgeStore.ConvergenceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 30 fork 2b — accumulates frontier knowledge across runs (8b): persists each
 * run's validated checkpoints with **keep-the-best** (highest-trust value per
 * {@code (bound, x)}), **ratchets** the frontier, and detects **convergence** so the
 * loop stops re-spending once results stabilize. Persisted via {@link
 * FrontierKnowledgeStore}, so it survives restart and the corpus is reusable by
 * future papers (trust carried verbatim — never laundered).
 */
@Service
public class FrontierKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(FrontierKnowledgeService.class);

    /** Outcome of recording a run. */
    public record RunRecord(int recordsKept, long frontier, int stableRuns, boolean converged) {}

    private final FrontierKnowledgeStore store;
    private final int convergenceThreshold;

    public FrontierKnowledgeService(
            FrontierKnowledgeStore store,
            @Value("${app.research.discovery.convergenceThreshold:2}") int convergenceThreshold) {
        this.store = store;
        this.convergenceThreshold = Math.max(1, convergenceThreshold);
    }

    public boolean isConverged(String bound) {
        return store.convergence(bound).converged();
    }

    public long frontier(String bound) {
        return store.frontierOf(bound);
    }

    /**
     * Persist a run's validated checkpoints (keep-the-best) and update convergence.
     * A run that didn't run (disabled/skipped) is ignored. Returns the new state.
     */
    public RunRecord record(String bound, EscalationOutcome outcome) {
        ConvergenceState prev = store.convergence(bound);
        if (outcome == null || !outcome.ran()) {
            return new RunRecord(0, prev.frontier(), prev.stableRuns(), prev.converged());
        }
        int kept = 0;
        for (EscalationCheckpoint c : outcome.checkpoints()) {
            if (c.verdict() == EscalationVerdict.HOLDS && c.value() != null) {
                // every validated rung is FULL trust (sieve exact / discovery moat-validated)
                if (keepBest(new FrontierRecord(bound, c.x(), c.value(), c.source(), TrustLevel.FULL))) {
                    kept++;
                }
            }
        }
        boolean progressed = outcome.frontierReached() > prev.frontier();
        long frontier = Math.max(prev.frontier(), outcome.frontierReached());
        int stable = progressed ? 0 : prev.stableRuns() + 1;
        boolean converged = stable >= convergenceThreshold;
        store.saveConvergence(bound, new ConvergenceState(frontier, stable, converged));
        log.info("frontier knowledge [{}] — kept {} record(s); frontier={}; progressed={}; stableRuns={}/{}; converged={}",
                bound, kept, frontier, progressed, stable, convergenceThreshold, converged);
        return new RunRecord(kept, frontier, stable, converged);
    }

    /** Put the record unless an equal-or-higher-trust value is already stored at (bound, x). */
    private boolean keepBest(FrontierRecord rec) {
        var existing = store.find(rec.bound(), rec.x());
        if (existing.isPresent() && existing.get().trust().ordinal() <= rec.trust().ordinal()) {
            return false;   // keep the existing (same or better trust)
        }
        store.put(rec);
        return true;
    }
}
