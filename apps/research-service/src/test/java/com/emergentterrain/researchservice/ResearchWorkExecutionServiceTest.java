package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ResearchWorkExecutionServiceTest {
    @Test
    void completesSupportedGenericWorkAndPersistsItsResult() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 3,
                Instant.now(), List.of(new ResearchSubject(UUID.randomUUID(), "AGENT", null)), null);
        ResearchWorkRequest plannedRequest = new ResearchWorkRequest(request.requestId(), request.workspaceId(),
                request.workType(), request.sourceRevision(), request.requestedAt(), request.subjects(),
                mapper.createObjectNode().set("draftSelection", mapper.valueToTree(
                        FrozenDraftSelectionFixtures.primaryInput(request.subjects().get(0).subjectId()))));
        ResearchWorkItem item = new ResearchWorkItem(plannedRequest.requestId(), plannedRequest.workspaceId(), plannedRequest.workType(),
                plannedRequest.sourceRevision(), mapper.writeValueAsString(plannedRequest), Instant.now());
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        ResearchWorkExecutionService service = new ResearchWorkExecutionService(repository,
                List.of(new GenericResearchCycleExecutor(mapper,
                        new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper))), mapper);

        service.execute(item);

        assertThat(item.getState()).isEqualTo("COMPLETED");
        assertThat(item.getResultJson()).contains("DRAFT_CANDIDATE_PLANNED");
        verify(repository).save(item);
    }

    @Test
    void persistsUnsupportedWorkAsFailed() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "UNSUPPORTED", 3,
                Instant.now(), List.of(), null);
        ResearchWorkItem item = new ResearchWorkItem(request.requestId(), request.workspaceId(), request.workType(),
                request.sourceRevision(), mapper.writeValueAsString(request), Instant.now());
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);

        new ResearchWorkExecutionService(repository, List.of(), mapper).execute(item);

        assertThat(item.getState()).isEqualTo("FAILED");
        verify(repository).save(item);
    }
}
