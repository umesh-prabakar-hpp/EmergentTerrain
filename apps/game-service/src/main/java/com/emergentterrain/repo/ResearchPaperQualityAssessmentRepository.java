package com.emergentterrain.repo;

import com.emergentterrain.research.quality.ResearchPaperQualityAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only Phase 43 AI quality-assessment history. */
public interface ResearchPaperQualityAssessmentRepository
        extends JpaRepository<ResearchPaperQualityAssessment, UUID> {

    Optional<ResearchPaperQualityAssessment> findFirstByPaperIdOrderByAssessmentRevisionDesc(String paperId);

    List<ResearchPaperQualityAssessment> findByPaperIdOrderByAssessmentRevisionDesc(String paperId);
}
