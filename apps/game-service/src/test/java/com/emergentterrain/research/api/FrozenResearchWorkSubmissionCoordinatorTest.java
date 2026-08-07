package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

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

        new FrozenResearchWorkSubmissionCoordinator(outbox)
                .submit(new FrozenResearchWorkSubmission(request, reservation));

        verify(outbox).enqueueReserved(request, reservation);
    }
}
