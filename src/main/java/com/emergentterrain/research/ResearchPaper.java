package com.emergentterrain.research;

import java.time.Instant;
import java.util.List;

public record ResearchPaper(
        String paperId,
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
        Instant publishedAt,
        int generation,
        double anomalyZScore   // z-score of peerScore vs room's domain+topic distribution; 0.0 if < 5 prior papers
) {}
