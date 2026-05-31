package com.emergentterrain.research.discovery;

import java.util.Optional;

/**
 * Phase 30 fork 2b — persistence abstraction for accumulated frontier knowledge.
 * The prod impl is DB-backed (survives restart); tests use an in-memory fake, so
 * {@link FrontierKnowledgeService}'s keep-the-best + convergence logic is unit-
 * testable without a database.
 */
public interface FrontierKnowledgeStore {

    /** Convergence bookkeeping per bound. */
    record ConvergenceState(long frontier, int stableRuns, boolean converged) {
        public static ConvergenceState initial() {
            return new ConvergenceState(0, 0, false);
        }
    }

    Optional<FrontierRecord> find(String bound, long x);

    void put(FrontierRecord record);

    /** Largest validated x for the bound (the accumulated frontier), or 0. */
    long frontierOf(String bound);

    ConvergenceState convergence(String bound);

    void saveConvergence(String bound, ConvergenceState state);
}
