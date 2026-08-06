package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenericResearchCycleExecutorTest {
    @Test
    void producesAReproducibleGameAgnosticBaseline() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(FrozenDraftSelectionFixtures.primaryInput(subjectId)));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 7,
                Instant.parse("2026-08-05T12:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
        FrozenResearchDraftPlanner planner = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper);
        ResearchResult result = new GenericResearchCycleExecutor(mapper, planner).execute(request);
        assertThat(result.outcome()).isEqualTo("DRAFT_CANDIDATE_PLANNED");
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.findings().path("subjectCount").asInt()).isEqualTo(1);
        assertThat(result.findings().path("sourceRevision").asLong()).isEqualTo(7L);
        assertThat(result.findings().path("draftEligibility").asText()).isEqualTo("CANDIDATE");
        assertThat(result.findings().path("draftSelectorVersion").asText()).isEqualTo("legacy-standard-catalog-v1");
    }
}
