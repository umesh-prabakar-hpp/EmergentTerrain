package com.emergentterrain.research.api;

import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.emergentterrain.researchcontract.ResearchPaperSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local compatibility adapter used until research moves into its own process. */
@Service
@ConditionalOnBean(UniversityService.class)
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "local", matchIfMissing = true)
public class LocalResearchQueryClient implements ResearchQueryClient {

    private final UniversityService universityService;

    public LocalResearchQueryClient(UniversityService universityService) {
        this.universityService = universityService;
    }

    @Override
    public List<ResearchPaperSummary> getPublishedPapers(UUID roomId, int limit) {
        return universityService.getPublishedPapers(roomId, limit).stream()
                .map(LocalResearchQueryClient::summary)
                .toList();
    }

    @Override
    public Map<String, Object> getStats(UUID roomId) {
        return universityService.getStats(roomId);
    }

    private static ResearchPaperSummary summary(ResearchPaper paper) {
        return new ResearchPaperSummary(
                paper.paperId(), paper.roomId(), paper.authorBotId(), paper.domain(), paper.topic(),
                paper.title(), paper.hypothesis(), paper.methodology(), paper.findings(), paper.conclusion(),
                List.copyOf(paper.reviewerBotIds()), paper.peerScore(), paper.status(), paper.tick(),
                paper.generation());
    }
}
