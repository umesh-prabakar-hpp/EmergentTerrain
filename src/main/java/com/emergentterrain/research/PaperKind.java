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
    META
}
