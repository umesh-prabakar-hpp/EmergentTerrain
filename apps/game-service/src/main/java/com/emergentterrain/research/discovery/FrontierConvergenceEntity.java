package com.emergentterrain.research.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Phase 30 fork 2b — persisted per-bound convergence state, keyed by the bound
 * name, so "converged" + the accumulated frontier survive a restart.
 */
@Entity
@Table(name = "frontier_convergence")
public class FrontierConvergenceEntity {

    @Id
    private String bound;

    @Column(nullable = false)
    private long frontier;

    @Column(name = "stable_runs", nullable = false)
    private int stableRuns;

    @Column(nullable = false)
    private boolean converged;

    private Instant updatedAt;

    protected FrontierConvergenceEntity() {}

    public FrontierConvergenceEntity(String bound) {
        this.bound = bound;
    }

    public void apply(FrontierKnowledgeStore.ConvergenceState s) {
        this.frontier = s.frontier();
        this.stableRuns = s.stableRuns();
        this.converged = s.converged();
        this.updatedAt = Instant.now();
    }

    public FrontierKnowledgeStore.ConvergenceState toState() {
        return new FrontierKnowledgeStore.ConvergenceState(frontier, stableRuns, converged);
    }
}
