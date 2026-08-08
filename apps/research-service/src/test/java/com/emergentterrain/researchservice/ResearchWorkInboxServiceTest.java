package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ResearchWorkInboxServiceTest {

    @Test
    void acceptsGenericWorkIntoResearchOwnedQueue() {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        UUID requestId = UUID.randomUUID();
        when(repository.findById(requestId)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 18,
                Instant.parse("2026-08-05T12:00:00Z"), null, null);

        ResearchWorkReceipt receipt = new ResearchWorkInboxService(repository, configuredMapper()).accept(request);

        verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq(request.workspaceId()),
                org.mockito.ArgumentMatchers.eq("RESEARCH_CYCLE"), org.mockito.ArgumentMatchers.eq(18L),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Instant.class));
        assertThat(receipt.requestId()).isEqualTo(requestId);
        assertThat(receipt.state()).isEqualTo("QUEUED");
    }

    @Test
    void acknowledgesAnExactReplayWithoutCreatingAnotherWorkItem() throws Exception {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        UUID requestId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-05T12:00:00Z");
        ObjectMapper mapper = configuredMapper();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                acceptedAt, null, null);
        ResearchWorkItem existing = new ResearchWorkItem(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                mapper.writeValueAsString(request), acceptedAt);
        when(repository.findById(requestId)).thenReturn(Optional.of(existing));

        ResearchWorkReceipt receipt = new ResearchWorkInboxService(repository, mapper).accept(request);

        assertThat(receipt.state()).isEqualTo("QUEUED");
        verify(repository).findById(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void acknowledgesAReplayWhoseStoredJsonUsesAnotherFieldOrder() throws Exception {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        UUID requestId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-05T12:00:00Z");
        ObjectMapper mapper = configuredMapper();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                acceptedAt, null, null);
        ObjectNode canonical = mapper.valueToTree(request);
        ObjectNode reordered = mapper.createObjectNode();
        for (String field : new String[] {"contextSnapshot", "subjects", "requestedAt", "sourceRevision",
                "workType", "workspaceId", "requestId"}) {
            JsonNode value = canonical.get(field);
            reordered.set(field, value);
        }
        ResearchWorkItem existing = new ResearchWorkItem(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                mapper.writeValueAsString(reordered), acceptedAt);
        when(repository.findById(requestId)).thenReturn(Optional.of(existing));

        ResearchWorkReceipt receipt = new ResearchWorkInboxService(repository, mapper).accept(request);

        assertThat(receipt.state()).isEqualTo("QUEUED");
        verify(repository).findById(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsAConflictingReplayAfterAnAtomicInsertRace() throws Exception {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        ObjectMapper mapper = configuredMapper();
        UUID requestId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-05T12:00:00Z");
        ResearchWorkRequest storedRequest = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                acceptedAt, null, null);
        ResearchWorkItem existing = new ResearchWorkItem(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                mapper.writeValueAsString(storedRequest), acceptedAt);
        ResearchWorkRequest conflicting = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 19,
                acceptedAt, null, null);
        when(repository.findById(requestId)).thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> new ResearchWorkInboxService(repository, mapper).accept(conflicting))
                .isInstanceOf(ResearchWorkReplayConflictException.class)
                .hasMessageContaining("cannot be rebound");
        verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.eq("RESEARCH_CYCLE"),
                org.mockito.ArgumentMatchers.eq(19L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void rejectsAConflictingReplayBeforeAnyQueueMutation() throws Exception {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        ObjectMapper mapper = configuredMapper();
        UUID requestId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-05T12:00:00Z");
        UUID workspaceId = UUID.randomUUID();
        ResearchWorkRequest original = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                acceptedAt, null, null);
        ResearchWorkItem existing = new ResearchWorkItem(requestId, workspaceId, "RESEARCH_CYCLE", 18,
                mapper.writeValueAsString(original), acceptedAt);
        when(repository.findById(requestId)).thenReturn(Optional.of(existing));
        ResearchWorkRequest conflicting = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 19,
                acceptedAt, null, null);

        assertThatThrownBy(() -> new ResearchWorkInboxService(repository, mapper).accept(conflicting))
                .isInstanceOf(ResearchWorkReplayConflictException.class)
                .hasMessageContaining("cannot be rebound");
        verify(repository).findById(requestId);
        verifyNoMoreInteractions(repository);
    }

    private static ObjectMapper configuredMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
