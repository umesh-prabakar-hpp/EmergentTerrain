package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.List;

/**
 * Read-only latest quality view for one research output.
 *
 * <p>This is distinct from workflow publication status. It contains no mutable paper or game
 * state, and is safe to expose through the v1 research query API.</p>
 */
public record ResearchQualitySummary(
        String grade,
        String rubricVersion,
        String assessorModel,
        int assessmentRevision,
        Instant assessedAt,
        List<String> unmetRequirements,
        List<String> contradictions) {

    public static ResearchQualitySummary unassessed() {
        return new ResearchQualitySummary("UNASSESSED", "", "", 0, null, List.of(), List.of());
    }
}
