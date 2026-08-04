package com.emergentterrain.research.api;

import com.emergentterrain.research.ResearchPaper;

import java.util.List;
import java.util.UUID;

/**
 * Stable, game-facing projection of a published paper.
 *
 * <p>The research service owns its persistent {@link ResearchPaper} entity.  Game clients must
 * use this DTO instead so entity fields and database mappings do not become part of the service
 * contract.</p>
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
        int generation) {

    public static ResearchPaperSummary from(ResearchPaper paper) {
        return new ResearchPaperSummary(
                paper.paperId(),
                paper.roomId(),
                paper.authorBotId(),
                paper.domain(),
                paper.topic(),
                paper.title(),
                paper.hypothesis(),
                paper.methodology(),
                paper.findings(),
                paper.conclusion(),
                List.copyOf(paper.reviewerBotIds()),
                paper.peerScore(),
                paper.status(),
                paper.tick(),
                paper.generation());
    }
}
