package com.emergentterrain.research.quality;

import java.util.List;

/**
 * Structured conclusion returned by the dedicated AI quality adjudicator.
 *
 * <p>The caller supplies this conclusion after an AI has inspected the retained evidence bundle.
 * It intentionally contains no computed score: deterministic code validates the evidence shape
 * and durable transition, but never derives the scholarly grade.
 */
public record AiResearchQualityAssessment(
        String paperId,
        String rubricVersion,
        String assessorModel,
        ResearchQualityGrade grade,
        String rationale,
        List<String> evidenceReferences,
        List<String> unmetRequirements,
        List<String> contradictions,
        String reviewerSummary,
        String replicationSummary,
        String benchmarkSummary
) {
    public AiResearchQualityAssessment {
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        unmetRequirements = unmetRequirements == null ? List.of() : List.copyOf(unmetRequirements);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }
}
