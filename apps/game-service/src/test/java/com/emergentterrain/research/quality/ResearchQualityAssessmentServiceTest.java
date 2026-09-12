package com.emergentterrain.research.quality;

import com.emergentterrain.repo.ResearchPaperQualityAssessmentRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchQualityAssessmentServiceTest {

    private ResearchPaperRepository papers;
    private ResearchPaperQualityAssessmentRepository assessments;
    private ResearchQualityAssessmentService service;

    @BeforeEach
    void setUp() {
        papers = mock(ResearchPaperRepository.class);
        assessments = mock(ResearchPaperQualityAssessmentRepository.class);
        when(papers.existsById("P-1")).thenReturn(true);
        when(assessments.findFirstByPaperIdOrderByAssessmentRevisionDesc("P-1"))
                .thenReturn(Optional.empty());
        when(assessments.save(any(ResearchPaperQualityAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new ResearchQualityAssessmentService(papers, assessments, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void persistsAnAiDecisionWithoutDerivingGradeFromComputation() {
        ResearchPaperQualityAssessment stored = service.recordAiAssessment(new AiResearchQualityAssessment(
                "P-1", "phase-43-rubric-v1", "quality-panel-v1", ResearchQualityGrade.RESEARCH_DRAFT,
                "The evidence bundle is coherent but independent replication is still pending.",
                List.of("protocol:P-1", "trace:P-1"),
                List.of("independent replication pending"), List.of(), "Two AI reviewers agree.", "", ""));

        assertThat(stored.assessmentRevision()).isEqualTo(1);
        assertThat(stored.grade()).isEqualTo(ResearchQualityGrade.RESEARCH_DRAFT);
        assertThat(stored.evidenceReferencesJson()).isEqualTo("[\"protocol:P-1\",\"trace:P-1\"]");
        assertThat(stored.assessedAt()).isEqualTo(Instant.parse("2026-09-12T12:00:00Z"));
    }

    @Test
    void rejectsASettledClaimWithoutReplicationAndReviewEvidence() {
        AiResearchQualityAssessment unsupported = new AiResearchQualityAssessment(
                "P-1", "phase-43-rubric-v1", "quality-panel-v1", ResearchQualityGrade.HUMAN_QUALITY_PAPER,
                "Looks promising.", List.of("trace:P-1"), List.of(), List.of(), "", "", "");

        assertThatThrownBy(() -> service.recordAiAssessment(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerSummary");
    }

    @Test
    void rejectsUnassessedAsAnAiConclusion() {
        AiResearchQualityAssessment unassessed = new AiResearchQualityAssessment(
                "P-1", "phase-43-rubric-v1", "quality-panel-v1", ResearchQualityGrade.UNASSESSED,
                "No conclusion.", List.of(), List.of("evidence incomplete"), List.of(), "", "", "");

        assertThatThrownBy(() -> service.recordAiAssessment(unassessed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNASSESSED");
    }
}
