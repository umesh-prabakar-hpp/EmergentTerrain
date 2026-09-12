package com.emergentterrain.research.quality;

import com.emergentterrain.repo.ResearchProtocolReuseRemediationAssessmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stores a bounded, fail-closed AI remediation judgement; it cannot alter paper or grade state. */
@Service
public class ProtocolReuseRemediationAssessmentService {
    private final ResearchProtocolReuseRemediationAssessmentRepository assessments;
    private final ObjectMapper mapper;

    public ProtocolReuseRemediationAssessmentService(
            ResearchProtocolReuseRemediationAssessmentRepository assessments, ObjectMapper mapper) {
        this.assessments = assessments; this.mapper = mapper;
    }

    @Transactional
    public ResearchProtocolReuseRemediationAssessment record(String paperId, String sourcePaperId,
            String assessorModel, ProtocolReuseRemediationOutcome outcome, String rationale, List<String> refs) {
        if (paperId == null || paperId.isBlank() || sourcePaperId == null || sourcePaperId.isBlank()
                || assessorModel == null || assessorModel.isBlank() || outcome == null
                || rationale == null || rationale.isBlank() || refs == null || refs.isEmpty() || refs.size() > 32) {
            throw new IllegalArgumentException("invalid protocol reuse remediation assessment");
        }
        int revision = assessments.findFirstByPaperIdOrderByAssessmentRevisionDesc(paperId)
                .map(previous -> Math.addExact(previous.assessmentRevision(), 1)).orElse(1);
        try {
            return assessments.save(new ResearchProtocolReuseRemediationAssessment(UUID.randomUUID(), paperId.strip(),
                    sourcePaperId.strip(), revision, assessorModel.strip(), outcome, rationale.strip(),
                    mapper.writeValueAsString(List.copyOf(refs)), Instant.now()));
        } catch (Exception error) {
            throw new IllegalStateException("could not persist protocol reuse remediation assessment", error);
        }
    }
}
