package com.emergentterrain.research.api;

import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.emergentterrain.research.quality.ResearchPaperQualityAssessment;
import com.emergentterrain.researchcontract.ResearchPaperSummary;
import com.emergentterrain.researchcontract.ResearchQualitySummary;
import com.emergentterrain.repo.ResearchPaperQualityAssessmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ResearchPaperQualityAssessmentRepository qualityAssessments;
    private final ObjectMapper objectMapper;

    public LocalResearchQueryClient(UniversityService universityService,
                                    ResearchPaperQualityAssessmentRepository qualityAssessments,
                                    ObjectMapper objectMapper) {
        this.universityService = universityService;
        this.qualityAssessments = qualityAssessments;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResearchPaperSummary> getPublishedPapers(UUID roomId, int limit) {
        return universityService.getPublishedPapers(roomId, limit).stream()
                .map(this::summary)
                .toList();
    }

    @Override
    public Map<String, Object> getStats(UUID roomId) {
        return universityService.getStats(roomId);
    }

    private ResearchPaperSummary summary(ResearchPaper paper) {
        return new ResearchPaperSummary(
                paper.paperId(), paper.roomId(), paper.authorBotId(), paper.domain(), paper.topic(),
                paper.title(), paper.hypothesis(), paper.methodology(), paper.findings(), paper.conclusion(),
                List.copyOf(paper.reviewerBotIds()), paper.peerScore(), paper.status(), paper.tick(),
                paper.generation(), quality(paper.paperId()));
    }

    private ResearchQualitySummary quality(String paperId) {
        return qualityAssessments.findFirstByPaperIdOrderByAssessmentRevisionDesc(paperId)
                .map(this::quality)
                .orElseGet(ResearchQualitySummary::unassessed);
    }

    private ResearchQualitySummary quality(ResearchPaperQualityAssessment assessment) {
        return new ResearchQualitySummary(
                assessment.grade().name(), assessment.rubricVersion(), assessment.assessorModel(),
                assessment.assessmentRevision(), assessment.assessedAt(),
                stringList(assessment.unmetRequirementsJson()), stringList(assessment.contradictionsJson()));
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<List<String>>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
