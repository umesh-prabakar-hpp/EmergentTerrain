package com.emergentterrain.research;

/**
 * Phase 4 Iter 9 — the structural relationship between a dependent variable
 * and its independent variables in a {@link FormalFinding}.
 *
 * Per the phase doc, the initial set is intentionally narrow:
 *   PROPORTIONAL, INVERSE, THRESHOLD, NULL.
 *
 * Phase 4b will add LOGARITHMIC and CATEGORICAL once the simpler types are
 * stable and the contradiction-detection rules are well-exercised.
 */
public enum RelationType {

    /** y increases monotonically with x (or each independent variable). */
    PROPORTIONAL,

    /** y decreases monotonically with x. */
    INVERSE,

    /** y exhibits a step or phase change at a specific value of x. */
    THRESHOLD,

    /** No reliable relationship found — the paper is a null result. */
    NULL
}
