package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchTopicHistoryEntry;
import com.emergentterrain.researchcontract.ResearchTopicHistorySnapshot;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FrozenResearchDraftPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void matchesTheFrozenGenericParityFixtures() throws Exception {
        try (InputStream fixtures = getClass().getResourceAsStream("/frozen-draft-selection-fixtures.json")) {
            assertThat(fixtures).isNotNull();
            for (JsonNode fixture : mapper.readTree(fixtures)) {
                ResearchDraftSelectionInput input = mapper.treeToValue(fixture.path("input"), ResearchDraftSelectionInput.class);
                ResearchDraftSelection actual = StandardResearchDraftSelector.select(input);
                JsonNode expected = fixture.path("expected");
                assertThat(actual.subjectId().toString()).isEqualTo(expected.path("subjectId").asText());
                assertThat(actual.eligibility()).isEqualTo(expected.path("eligibility").asText());
                assertThat(actual.domain()).isEqualTo(expected.path("domain").asText());
                assertThat(actual.topic()).isEqualTo(expected.path("topic").asText());
            }
        }
    }

    @Test
    void preservesHistoryAwareTopicSelectionAcrossTheWorkRequestJsonBoundary() throws Exception {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 43L,
                "WEIGHTED", List.of(new ResearchDraftCandidate(subjectId, "PRIMARY", 1.0, "MATHEMATICS",
                List.of(new ResearchTopicOption("cooled", 1.0), new ResearchTopicOption("fresh", 1.0)))),
                ResearchDraftExecutionIntent.standardCatalog(), new ResearchTopicHistorySnapshot("legacy-topic-history-v1",
                List.of(new ResearchTopicHistoryEntry("MATHEMATICS", "cooled", "REJECTED", 0.2))),
                new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1", true, false, 2, 0.0, 1.0));
        var context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchDraftPlan plan = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper)
                .plan(mapper.readValue(mapper.writeValueAsString(request), ResearchWorkRequest.class));

        assertThat(plan.topic()).isEqualTo(StandardResearchDraftSelector.select(input).topic());
        assertThat(plan.inputFingerprint()).isNotBlank();
    }
}
