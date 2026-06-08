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
    CONJECTURE_INVESTIGATION,

    /**
     * Phase 30 Wave 7 — 30.6: an UNEXPLAINED_OBSERVATION investigation of an
     * anomaly that survived the adversarial promotion gate (a bound that held
     * >= N runs, broke surprisingly hard, replicated under fresh refutation
     * attempts, and matches no OEIS entry). At publish, papers of this kind
     * seed a {@link ProgramOpenQuestion} so future cycles deliberately
     * re-target the anomaly. Safe enum addition: persisted via
     * {@link PaperKindConverter} (@Convert, name string — no CHECK constraint).
     */
    ANOMALY_PROMOTION,

    /**
     * Phase 31 §31.7 — a SYNTHESIS (review) paper composed over the room's own
     * prior papers on one topic family: what they collectively say, where they
     * conflict, what holds, what is open. Outline-first, multi-section, every
     * claim cited by paperId; evidence label capped at the weakest cited
     * source ({@link com.emergentterrain.research.synthesis.SynthesisLabelCap}).
     * Safe enum addition: persisted via {@link PaperKindConverter} (@Convert,
     * name string — no CHECK constraint to go stale).
     */
    SYNTHESIS
}
