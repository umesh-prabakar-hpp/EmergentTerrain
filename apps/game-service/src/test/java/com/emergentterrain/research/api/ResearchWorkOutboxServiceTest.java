package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

class ResearchWorkOutboxServiceTest {

    @Test
    void persistsOneDeterministicallyIdentifiedWorkRequest() {
        ResearchWorkOutboxRepository repository = mock(ResearchWorkOutboxRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.parse("2026-08-05T12:00:00Z"), null, null);

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

    private static ObjectMapper configuredMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
