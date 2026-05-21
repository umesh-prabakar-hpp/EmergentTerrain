package com.emergentterrain.research;

/**
 * Phase 4 Iter 10 — distinguishes domain papers (the simulation's actual research
 * output) from meta papers (analyses of the system itself, produced by
 * {@link MetaResearchService}).
 *
 * Meta-papers are subject to the same paper pipeline (peer review, citations,
 * markets) but should not skew per-domain statistics or dominate citation
 * rankings against domain papers. Consumers that care about the distinction
 * filter on {@link ResearchPaper#kind()}.
 *
 * Default is {@link #DOMAIN} so existing papers (pre-Iter-10) read back as
 * domain papers without a data migration.
 */
public enum PaperKind {
    /** Ordinary research output — the simulation's actual computational papers. */
    DOMAIN,

    /** A paper *about* the simulation: per-prompt / per-bot / per-method analyses. */
    META,

    /**
     * Phase 23.4 — a paper investigating a specific mined conjecture from
     * {@link com.emergentterrain.research.conjecture.ConjectureMinerService}.
     * Hypothesis is the conjecture's inequality verbatim; the LLM's job is
     * mechanism + adversarial verification, not topic selection. The full
     * prompt-template + verify_conjecture tool integration lands with 23.5;
     * this value is the persistence-side handle so the kind can be recorded
     * once the upstream wiring is ready.
     */
    CONJECTURE_INVESTIGATION
}
