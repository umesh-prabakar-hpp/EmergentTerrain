package com.emergentterrain.research;

/**
 * Phase 4 Iter 9 — the structural relationship between a dependent variable
 * and its independent variables in a {@link FormalFinding}.
 *
 * The initial set shipped intentionally narrow: PROPORTIONAL, INVERSE,
 * THRESHOLD, NULL. LOGARITHMIC and CATEGORICAL landed after the simpler
 * types were exercised, with deliberately limited semantic integration:
 * <ul>
 *   <li>{@link KnowledgeBaseService}'s composition table skips both new
 *       types (composition through a logarithmic step loses information;
 *       categorical relations can't compose with continuous monotonic ones
 *       at all). This is the same treatment {@link #THRESHOLD} already
 *       gets — these types are persisted and surfaced but don't generate
 *       derived theory questions yet.</li>
 *   <li>Contradiction detection adds one new rule: {@link #LOGARITHMIC}
 *       and {@link #INVERSE} flag a contradiction (both monotonic,
 *       opposite directions). {@link #CATEGORICAL} doesn't compare with
 *       continuous types — it's about discrete class structure, so a
 *       paper claiming PROPORTIONAL and another claiming CATEGORICAL
 *       are describing different things, not disagreeing.</li>
 *   <li>{@link #NULL} vs any non-NULL still counts as a contradiction
 *       — the no-relationship claim genuinely disputes both monotonic
 *       and categorical claims.</li>
 * </ul>
 */
public enum RelationType {

    /** y increases monotonically with x (or each independent variable). */
    PROPORTIONAL,

    /** y decreases monotonically with x. */
    INVERSE,

    /** y exhibits a step or phase change at a specific value of x. */
    THRESHOLD,

    /** y rises monotonically but sub-linearly in x (log-shaped curve). */
    LOGARITHMIC,

    /** y takes on discrete classes selected by x — no continuous monotonic ordering. */
    CATEGORICAL,

    /** No reliable relationship found — the paper is a null result. */
    NULL
}
