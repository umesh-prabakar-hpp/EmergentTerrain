package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ConjectureTopicProvider;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deterministic guardrails for the deliberately unwired conjecture-consumption cutover seam. */
class FrozenResearchConjectureConsumptionCoordinatorTest {
    @Test
    void consumesTheMatchingSourceOnceAndRecordsTheClaim() {
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        ResearchWorkSourceReservationRepository reservations = mock(ResearchWorkSourceReservationRepository.class);
        ConjectureTopicProvider conjectures = mock(ConjectureTopicProvider.class);
        FrozenResearchConjectureConsumptionCoordinator coordinator = coordinator(outbox, reservations, conjectures);
        UUID sourceId = UUID.randomUUID();
        FrozenResearchWorkSubmission submission = submission(sourceId);
        Conjecture observed = observed(sourceId);
        when(reservations.claimForConsumption(eq(submission.request().requestId()), eq("CONJECTURE"), eq(sourceId.toString()),
                org.mockito.ArgumentMatchers.any())).thenReturn(1, 0);

        coordinator.submitAndConsume(submission, observed);
        coordinator.submitAndConsume(submission, observed);

        verify(outbox, times(2)).enqueueReserved(submission.request(), submission.reservation());
        verify(reservations, times(2)).claimForConsumption(eq(submission.request().requestId()), eq("CONJECTURE"),
                eq(sourceId.toString()), org.mockito.ArgumentMatchers.any());
        verify(conjectures, times(1)).consumeObservedConjecture(observed);
    }

    @Test
    void doesNotClaimOrConsumeWhenDurableHandoffFails() {
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        ResearchWorkSourceReservationRepository reservations = mock(ResearchWorkSourceReservationRepository.class);
        ConjectureTopicProvider conjectures = mock(ConjectureTopicProvider.class);
        FrozenResearchConjectureConsumptionCoordinator coordinator = coordinator(outbox, reservations, conjectures);
        UUID sourceId = UUID.randomUUID();
        FrozenResearchWorkSubmission submission = submission(sourceId);
        Conjecture observed = observed(sourceId);
        doThrow(new IllegalStateException("outbox write failed"))
                .when(outbox).enqueueReserved(submission.request(), submission.reservation());

        assertThatThrownBy(() -> coordinator.submitAndConsume(submission, observed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox write failed");

        verify(reservations, never()).claimForConsumption(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(conjectures, never()).consumeObservedConjecture(observed);
    }

    @Test
    void rejectsADifferentLiveSourceBeforeWritingTheHandoff() {
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        ResearchWorkSourceReservationRepository reservations = mock(ResearchWorkSourceReservationRepository.class);
        ConjectureTopicProvider conjectures = mock(ConjectureTopicProvider.class);
        FrozenResearchConjectureConsumptionCoordinator coordinator = coordinator(outbox, reservations, conjectures);
        FrozenResearchWorkSubmission submission = submission(UUID.randomUUID());

        assertThatThrownBy(() -> coordinator.submitAndConsume(submission, observed(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");

        verify(outbox, never()).enqueueReserved(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(reservations, never()).claimForConsumption(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static FrozenResearchWorkSubmission submission(UUID sourceId) {
        UUID requestId = UUID.randomUUID();
        ResearchWorkRequest request = new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 42,
                Instant.parse("2026-08-10T00:00:00Z"), null, null);
        return new FrozenResearchWorkSubmission(request,
                new ResearchWorkSourceReservation(requestId, "CONJECTURE", sourceId.toString()));
    }

    private static Conjecture observed(UUID sourceId) {
        Conjecture conjecture = new Conjecture("math", "lhs", "rhs", 1.0, 0.0);
        ReflectionTestUtils.setField(conjecture, "id", sourceId);
        return conjecture;
    }

    private static FrozenResearchConjectureConsumptionCoordinator coordinator(ResearchWorkOutboxService outbox,
                                                                               ResearchWorkSourceReservationRepository reservations,
                                                                               ConjectureTopicProvider conjectures) {
        return new FrozenResearchConjectureConsumptionCoordinator(outbox, reservations, conjectures);
    }
}
