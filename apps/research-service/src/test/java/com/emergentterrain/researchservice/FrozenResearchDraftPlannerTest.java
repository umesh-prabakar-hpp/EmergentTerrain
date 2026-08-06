package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

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
}
