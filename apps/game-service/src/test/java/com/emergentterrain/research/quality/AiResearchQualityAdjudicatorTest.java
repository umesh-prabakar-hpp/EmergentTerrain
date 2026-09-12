package com.emergentterrain.research.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiResearchQualityAdjudicatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> allowed = Set.of("paper:P-1", "protocol:P-1", "computation:P-1");

    @Test
    void acceptsAConservativeAiDraftWithBoundedEvidenceReferences() {
        String response = """
                {"grade":"RESEARCH_DRAFT","rationale":"The methods and findings are coherent.",
                 "evidence_references":["paper:P-1","protocol:P-1"],
                 "unmet_requirements":["independent replication pending"],
                 "contradictions":[],"reviewer_summary":"No panel disagreement recorded."}
                """;

        assertThat(AiResearchQualityAdjudicator.parseResponse(
                mapper, response, "P-1", "phase-43-rubric-v1", "quality-model", allowed))
                .hasValueSatisfying(decision -> {
                    assertThat(decision.grade()).isEqualTo(ResearchQualityGrade.RESEARCH_DRAFT);
                    assertThat(decision.evidenceReferences()).containsExactlyInAnyOrderElementsOf(allowed);
                });
    }

    @Test
    void bindsTheAiJudgedEvidenceBundleToExactSuppliedRecords() {
        String response = """
                {"grade":"RESEARCH_DRAFT","rationale":"The methods and findings are coherent.",
                 "unmet_requirements":["independent replication pending"],
                 "contradictions":[],"reviewer_summary":"No panel disagreement recorded."}
                """;

        assertThat(AiResearchQualityAdjudicator.parseResponse(
                mapper, response, "P-1", "phase-43-rubric-v1", "quality-model", allowed))
                .hasValueSatisfying(decision ->
                        assertThat(decision.evidenceReferences()).containsExactlyInAnyOrderElementsOf(allowed));
    }

    @Test
    void acceptsAConservativeDecisionWrappedInMarkdownFencing() {
        String response = """
                Here is the requested decision:
                ```json
                {"grade":"RESEARCH_NOTE","rationale":"Evidence remains preliminary.",
                 "evidence_references":["paper:P-1"],
                 "unmet_requirements":["independent replication pending"],
                 "contradictions":[],"reviewer_summary":"No panel artifact is available."}
                ```
                """;

        assertThat(AiResearchQualityAdjudicator.parseResponse(
                mapper, response, "P-1", "phase-43-rubric-v1", "quality-model", allowed))
                .hasValueSatisfying(decision ->
                        assertThat(decision.grade()).isEqualTo(ResearchQualityGrade.RESEARCH_NOTE));
    }

    @Test
    void rejectsUnsupportedHumanQualityAndDoesNotPersistModelSuppliedEvidenceIdentifiers() {
        String humanQuality = """
                {"grade":"HUMAN_QUALITY_PAPER","rationale":"Claimed complete.",
                 "evidence_references":["paper:P-1"],"unmet_requirements":[],
                 "contradictions":[],"reviewer_summary":"Claimed."}
                """;
        String inventedEvidence = """
                {"grade":"RESEARCH_NOTE","rationale":"Claimed evidence.",
                 "evidence_references":["replication:P-1"],"unmet_requirements":[],
                 "contradictions":[],"reviewer_summary":""}
                """;

        assertThat(AiResearchQualityAdjudicator.parseResponse(
                mapper, humanQuality, "P-1", "v1", "quality-model", allowed)).isEmpty();
        assertThat(AiResearchQualityAdjudicator.parseResponse(
                mapper, inventedEvidence, "P-1", "v1", "quality-model", allowed))
                .hasValueSatisfying(decision ->
                        assertThat(decision.evidenceReferences()).containsExactlyInAnyOrderElementsOf(allowed));
    }
}
