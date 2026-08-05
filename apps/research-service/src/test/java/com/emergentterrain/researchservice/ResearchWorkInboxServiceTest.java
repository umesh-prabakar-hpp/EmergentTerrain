package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    void acknowledgesAReplayWithoutCreatingAnotherWorkItem() {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        UUID requestId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-05T12:00:00Z");
        ResearchWorkItem existing = new ResearchWorkItem(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 18,
                "{}", acceptedAt);
        when(repository.findById(requestId)).thenReturn(Optional.of(existing));
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, existing.getWorkspaceId(), "RESEARCH_CYCLE", 18,
                acceptedAt, null, null);

        ResearchWorkReceipt receipt = new ResearchWorkInboxService(repository, configuredMapper()).accept(request);

        assertThat(receipt.state()).isEqualTo("QUEUED");
        verify(repository).findById(requestId);
        verifyNoMoreInteractions(repository);
    }

    private static ObjectMapper configuredMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
