package com.emergentterrain.research.quality;

import com.emergentterrain.repo.ResearchPaperQualityAssessmentRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists conclusions from a dedicated AI quality adjudicator.
 *
 * <p>This service does not calculate a grade from paper metadata, computation, or peer score.
 * Its deterministic validation only prevents unsupported claims and malformed evidence records.
 */
@Service
public class ResearchQualityAssessmentService {

    private final ResearchPaperRepository papers;
    private final ResearchPaperQualityAssessmentRepository assessments;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ResearchQualityAssessmentService(
            ResearchPaperRepository papers,
            ResearchPaperQualityAssessmentRepository assessments,
            ObjectMapper objectMapper) {
        this(papers, assessments, objectMapper, Clock.systemUTC());
    }

    ResearchQualityAssessmentService(
            ResearchPaperRepository papers,
            ResearchPaperQualityAssessmentRepository assessments,
            ObjectMapper objectMapper,
            Clock clock) {
        this.papers = papers;
        this.assessments = assessments;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ResearchPaperQualityAssessment recordAiAssessment(AiResearchQualityAssessment decision) {
        require(decision != null, "AI quality assessment is required");
        requireText(decision.paperId(), "paperId");
        requireText(decision.rubricVersion(), "rubricVersion");
        requireText(decision.assessorModel(), "assessorModel");
        require(decision.grade() != null && decision.grade() != ResearchQualityGrade.UNASSESSED,
                "an AI assessment must assign a grade other than UNASSESSED");
        requireText(decision.rationale(), "rationale");
        requireList(decision.evidenceReferences(), "evidenceReferences");
        require(!decision.evidenceReferences().isEmpty(),
                "an AI quality assessment requires evidence references");
        requireList(decision.unmetRequirements(), "unmetRequirements");
        requireList(decision.contradictions(), "contradictions");
        require(papers.existsById(decision.paperId()), "paper does not exist: " + decision.paperId());

        if (decision.grade().settlesProtocol()) {
            require(!decision.evidenceReferences().isEmpty(),
                    "a settled quality grade requires evidence references");
            require(decision.unmetRequirements().isEmpty(),
                    "a settled quality grade cannot retain unmet requirements");
            requireText(decision.reviewerSummary(), "reviewerSummary for settled grade");
            requireText(decision.replicationSummary(), "replicationSummary for settled grade");
        }
        if (decision.grade() == ResearchQualityGrade.BEYOND_HUMAN_BENCHMARK) {
            requireText(decision.benchmarkSummary(), "benchmarkSummary for beyond-human grade");
        }

        int revision = assessments.findFirstByPaperIdOrderByAssessmentRevisionDesc(decision.paperId())
                .map(prior -> Math.addExact(prior.assessmentRevision(), 1))
                .orElse(1);
        return assessments.save(new ResearchPaperQualityAssessment(
                UUID.randomUUID(),
                decision.paperId().strip(),
                revision,
                decision.rubricVersion().strip(),
                decision.assessorModel().strip(),
                decision.grade(),
                decision.rationale().strip(),
                json(decision.evidenceReferences()),
                json(decision.unmetRequirements()),
                json(decision.contradictions()),
                trimToNull(decision.reviewerSummary()),
                trimToNull(decision.replicationSummary()),
                trimToNull(decision.benchmarkSummary()),
                Instant.now(clock)));
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize AI quality assessment evidence", e);
        }
    }

    private static void requireList(List<String> values, String name) {
        require(values != null && values.size() <= 128, name + " must contain at most 128 entries");
        for (String value : values) {
            requireText(value, name + " entry");
        }
    }

    private static void requireText(String value, String name) {
        require(value != null && !value.isBlank(), name + " is required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
