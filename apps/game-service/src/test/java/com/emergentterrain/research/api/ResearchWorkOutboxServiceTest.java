package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchWorkOutboxServiceTest {

    @Test
    void persistsOneDeterministicallyIdentifiedWorkRequest() {
        ResearchWorkOutboxRepository repository = mock(ResearchWorkOutboxRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.parse("2026-08-05T12:00:00Z"), null, null);
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(1);

        new ResearchWorkOutboxService(repository, configuredMapper()).enqueue(request);

        verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq("RESEARCH_CYCLE"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void delegatesConflictHandlingToTheAtomicInsert() {
        ResearchWorkOutboxRepository repository = mock(ResearchWorkOutboxRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.now(), null, null);
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(1);

        new ResearchWorkOutboxService(repository, configuredMapper()).enqueue(request);

        verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq("RESEARCH_CYCLE"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void persistsRequestKeyedReservationBeforeTheOutboxRequest() {
        ResearchWorkOutboxRepository outbox = mock(ResearchWorkOutboxRepository.class);
        ResearchWorkSourceReservationRepository reservations = mock(ResearchWorkSourceReservationRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.now(), null, null);
        when(outbox.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(1);
        when(reservations.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(1);

        new ResearchWorkOutboxService(outbox, reservations, configuredMapper()).enqueueReserved(request,
                new ResearchWorkSourceReservation(requestId, "CONJECTURE", "conjecture-7"));

        var order = inOrder(reservations, outbox);
        order.verify(reservations).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq("CONJECTURE"), org.mockito.ArgumentMatchers.eq("conjecture-7"),
                org.mockito.ArgumentMatchers.any(Instant.class));
        order.verify(outbox).insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq("RESEARCH_CYCLE"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void rejectsRebindingAnExistingRequestToAnotherSource() {
        ResearchWorkOutboxRepository outbox = mock(ResearchWorkOutboxRepository.class);
        ResearchWorkSourceReservationRepository reservations = mock(ResearchWorkSourceReservationRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.now(), null, null);
        when(reservations.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(0);
        when(reservations.findById(requestId)).thenReturn(Optional.of(
                new com.emergentterrain.domain.ResearchWorkSourceReservationEntry(
                        requestId, "CONJECTURE", "conjecture-7")));

        ResearchWorkOutboxService service = new ResearchWorkOutboxService(outbox, reservations, configuredMapper());

        assertThatThrownBy(() -> service.enqueueReserved(request,
                new ResearchWorkSourceReservation(requestId, "CONJECTURE", "conjecture-8")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound");
        verifyNoInteractions(outbox);
    }

    @Test
    void permitsAnExactOutboxPayloadReplay() throws Exception {
        ResearchWorkOutboxRepository repository = mock(ResearchWorkOutboxRepository.class);
        ObjectMapper mapper = configuredMapper();
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.parse("2026-08-05T12:00:00Z"), null, null);
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(0);
        when(repository.findById(requestId)).thenReturn(Optional.of(
                new com.emergentterrain.domain.ResearchWorkOutboxEntry(requestId, "RESEARCH_CYCLE",
                        mapper.writeValueAsString(request))));

        new ResearchWorkOutboxService(repository, mapper).enqueue(request);

        verify(repository).findById(requestId);
    }

    @Test
    void rejectsRebindingAnExistingRequestToAnotherPayload() {
        ResearchWorkOutboxRepository repository = mock(ResearchWorkOutboxRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.parse("2026-08-05T12:00:00Z"), null, null);
        when(repository.insertIfAbsent(org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(0);
        when(repository.findById(requestId)).thenReturn(Optional.of(
                new com.emergentterrain.domain.ResearchWorkOutboxEntry(requestId, "RESEARCH_CYCLE", "{}")));

        assertThatThrownBy(() -> new ResearchWorkOutboxService(repository, configuredMapper()).enqueue(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound");
    }

    private static ObjectMapper configuredMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
