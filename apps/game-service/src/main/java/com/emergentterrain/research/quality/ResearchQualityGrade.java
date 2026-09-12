package com.emergentterrain.research.quality;

/**
 * AI-adjudicated scholarly quality, deliberately separate from a paper's workflow status and
 * deterministic computational-artifact label.
 *
 * <p>{@link #UNASSESSED} is represented by the absence of a durable assessment. It is included
 * here for reads and policy decisions, but an adjudicator may not persist it as a conclusion.
 */
public enum ResearchQualityGrade {
    UNASSESSED,
    RESEARCH_NOTE,
    RESEARCH_DRAFT,
    HUMAN_QUALITY_PAPER,
    BEYOND_HUMAN_BENCHMARK;

    /** A result at either settled grade blocks ordinary reuse of its exact protocol. */
    public boolean settlesProtocol() {
        return this == HUMAN_QUALITY_PAPER || this == BEYOND_HUMAN_BENCHMARK;
    }
}
