package com.emergentterrain.research;

/**
 * Phase 4c Piece 2 gate 3: stable identifiers for the inline prompt templates
 * surveyed in {@code phase-4c-piece-1-memo.md}. Each {@code (id, version)} pair
 * names exactly one template body so that downstream replication outcomes can
 * be attributed to the prompt that produced them.
 *
 * Today there is one variant per template — every constant pins {@code v1}. When
 * Phase 4c Piece 3 lands, the {@code PromptTemplateRegistry} will resolve a
 * template id to one of several active variants and write the chosen version
 * back through the {@link NarrationResult#templateVersion()} field on the
 * persisted {@link ResearchPaper}.
 *
 * Adding a new template here without a corresponding migration of the
 * narration callsite is harmless — the constant is only meaningful once a
 * callsite stamps it onto a {@link NarrationResult}. The intent is that
 * <em>every</em> inline prompt eventually has its identity declared here, so
 * Piece 2's mechanical refactor has a single source of truth to migrate against.
 */
public final class PromptTemplateIds {

    private PromptTemplateIds() {}

    // --- Narration pipeline (com.emergentterrain.service.ResearchNarrationService) ---

    /** Primary first-pass narration prompt — produces the initial findings JSON. */
    public static final String NARRATION_PRIMARY_ID      = "narration.primary";
    public static final String NARRATION_PRIMARY_VERSION = "v1";

    /** Refinement pass when the primary needed an additional iteration. */
    public static final String NARRATION_REFINEMENT_ID      = "narration.refinement";
    public static final String NARRATION_REFINEMENT_VERSION = "v1";

    /** Self-critique pass that evaluates the first-pass narration. */
    public static final String NARRATION_CRITIQUE_ID      = "narration.critique";
    public static final String NARRATION_CRITIQUE_VERSION = "v1";

    /** Audit pass against the first-pass narration. */
    public static final String NARRATION_AUDIT_ID      = "narration.audit";
    public static final String NARRATION_AUDIT_VERSION = "v1";

    /** Professor system / user prompts in the tools-calling collaboration loop. */
    public static final String NARRATION_PROFESSOR_SYSTEM_ID      = "narration.professor.system";
    public static final String NARRATION_PROFESSOR_SYSTEM_VERSION = "v1";
    public static final String NARRATION_PROFESSOR_USER_ID        = "narration.professor.user";
    public static final String NARRATION_PROFESSOR_USER_VERSION   = "v1";

    /** Revision pass after critique/audit feedback has been incorporated. */
    public static final String NARRATION_REVISION_ID      = "narration.revision";
    public static final String NARRATION_REVISION_VERSION = "v1";

    /** Statistical-framing pass that re-shapes raw findings into testable claims. */
    public static final String NARRATION_STATISTICAL_FRAMING_ID      = "narration.statistical_framing";
    public static final String NARRATION_STATISTICAL_FRAMING_VERSION = "v1";

    /**
     * Phase 7 F7.1 — multi-reviewer panel review. Each reviewer LLM sees the
     * draft + the full tool-call trace and returns a structured JSON grade plus
     * lists of cited / omitted / contradicting calls. F7.2's panel orchestrator
     * fans this out to N providers in parallel.
     */
    public static final String NARRATION_PANEL_REVIEW_ID      = "narration.panel_review";
    public static final String NARRATION_PANEL_REVIEW_VERSION = "v2-with-evidence-label";

    // --- Formulation pipeline (com.emergentterrain.service.FormulationService) ---

    /** Pre-computation hypothesis + parameter selection prompt. */
    public static final String FORMULATION_MAIN_ID      = "formulation.main";
    public static final String FORMULATION_MAIN_VERSION = "v1";

    /** Novelty-saturation assessment for a domain+topic before formulation. */
    public static final String FORMULATION_NOVELTY_ID      = "formulation.novelty";
    public static final String FORMULATION_NOVELTY_VERSION = "v1";

    // --- Mentoring pipeline (com.emergentterrain.service.GenomeMentoringService) ---

    /** Genome-edit + dynamic-directive prompt issued after a high-quality publication. */
    public static final String MENTORING_GENOME_ID      = "mentoring.genome";
    public static final String MENTORING_GENOME_VERSION = "v1";
}
