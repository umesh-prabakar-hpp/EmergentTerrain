package com.emergentterrain.repo;

import com.emergentterrain.research.quality.ResearchProtocolReuseRemediationAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResearchProtocolReuseRemediationAssessmentRepository
        extends JpaRepository<ResearchProtocolReuseRemediationAssessment, UUID> {
    Optional<ResearchProtocolReuseRemediationAssessment> findFirstByPaperIdOrderByAssessmentRevisionDesc(String paperId);
}
