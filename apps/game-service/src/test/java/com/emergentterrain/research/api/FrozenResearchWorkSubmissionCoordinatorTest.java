package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FrozenResearchWorkSubmissionCoordinatorTest {
    @Test
    void delegatesOnlyAnAlreadyFrozenMatchingSubmission() {
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.now(), null, null);
        ResearchWorkSourceReservation reservation = new ResearchWorkSourceReservation(requestId, "CONJECTURE", "c-7");

        new FrozenResearchWorkSubmissionCoordinator(new GameResearchWorkMapper(new ObjectMapper(), false), outbox)
                .submit(new FrozenResearchWorkSubmission(request, reservation));

        verify(outbox).enqueueReserved(request, reservation);
    }

    @Test
    void rejectsAReservationForAnotherRequestBeforeCoordinatorUse() {
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 12,
                Instant.now(), null, null);

        assertThatThrownBy(() -> new FrozenResearchWorkSubmission(request,
                new ResearchWorkSourceReservation(UUID.randomUUID(), "CONJECTURE", "c-7")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    @Test
    void relaysAnObservedFrozenConjectureDecisionWithoutQueueAccess() {
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        ObjectMapper mapper = new ObjectMapper();
        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, false).fromTick(UUID.randomUUID(),
                java.util.List.of(), 1L, null);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                "legacy-conjecture-v1", "c-7", "math", "BOUND", "lhs", "rhs", null,
                1, 0, 0, 1, 0.1, null, "QUEUE_CANDIDATE");

        new FrozenResearchWorkSubmissionCoordinator(new GameResearchWorkMapper(mapper, false), outbox)
                .submitFrozenConjecture(request, new ResearchConjectureQueueSelection(conjecture, 1));

        verify(outbox).enqueueReserved(org.mockito.ArgumentMatchers.argThat(
                        frozen -> frozen.requestId().equals(request.requestId())),
                org.mockito.ArgumentMatchers.argThat(reservation -> reservation.sourceId().equals("c-7")));
    }
}
