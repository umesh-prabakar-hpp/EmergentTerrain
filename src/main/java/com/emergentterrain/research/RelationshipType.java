package com.emergentterrain.research;

/**
 * Phase 1 Iter 3 — typed relationship between two papers.
 *
 * The default for unclassified edges is {@link #EXTENDS} (a generic
 * citation). LLM narration may classify edges as one of the more specific
 * relationships when it can identify the rhetorical role of the citation.
 */
public enum RelationshipType {

    /** Citing paper presents results that confirm the cited paper's findings. */
    SUPPORTS,

    /** Citing paper's results are inconsistent with the cited paper. */
    CONTRADICTS,

    /** Citing paper extends or builds on the cited paper without disputing it. */
    EXTENDS,

    /** Citing paper attempted to reproduce the cited paper's experiment. */
    REPLICATES,

    /** Citing paper questions methodology or interpretation without contradicting results. */
    CRITICIZES,

    /** Citing paper inherits a theoretical framework from the cited paper. */
    DERIVES_FROM
}
