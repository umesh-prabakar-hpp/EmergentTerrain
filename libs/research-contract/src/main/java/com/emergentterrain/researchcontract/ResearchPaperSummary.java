package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.UUID;

/**
 * Stable projection of a published paper shared across the v1 service boundary.
 *
 * <p>The contract deliberately has no dependency on a persistence entity or either Spring
 * application. Keep database mappings and service-local conversion outside this module.</p>
 */
public record ResearchPaperSummary(
        String paperId,
        UUID roomId,
        String authorBotId,
        String domain,
        String topic,
        String title,
        String hypothesis,
        String methodology,
        String findings,
        String conclusion,
        List<String> reviewerBotIds,
        double peerScore,
        String status,
        long tick,
        int generation,
        ResearchQualitySummary quality) { }
