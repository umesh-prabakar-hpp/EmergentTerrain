package com.emergentterrain.researchservice;

import java.util.List;
import java.util.UUID;

/** JSON representation defined by contracts/research-api/openapi.yaml. */
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
        int generation) { }
