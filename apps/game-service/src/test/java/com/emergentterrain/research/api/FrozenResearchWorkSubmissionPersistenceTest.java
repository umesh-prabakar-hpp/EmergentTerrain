package com.emergentterrain.research.api;

import com.emergentterrain.EmergentTerrainApplication;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the frozen conjecture handoff uses one durable game-owned reservation/outbox pair. */
@SpringBootTest(classes = EmergentTerrainApplication.class)
@ActiveProfiles("test")
class FrozenResearchWorkSubmissionPersistenceTest {
    @Autowired private GameResearchWorkMapper mapper;
    @Autowired private FrozenResearchWorkSubmissionCoordinator coordinator;
    @Autowired private ResearchWorkOutboxRepository outbox;
    @Autowired private ResearchWorkSourceReservationRepository reservations;

    @Test
    void persistsOneMatchingHandoffAndSafelyReplaysIt() {
        UUID roomId = UUID.randomUUID();
        ResearchWorkRequest request = mapper.fromTick(roomId, List.of(), 1L, null);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                "legacy-conjecture-v1", "integration-conjecture", "math", "BOUND", "lhs", "rhs", null,
                1, 0, 0, 1, 0.1, null, "QUEUE_CANDIDATE");
        ResearchConjectureQueueSelection selection = new ResearchConjectureQueueSelection(conjecture, 1);

        coordinator.submitFrozenConjecture(request, selection);
        coordinator.submitFrozenConjecture(request, selection);

        assertThat(outbox.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getWorkType()).isEqualTo("RESEARCH_CYCLE");
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getReservationType(), entry -> entry.getSourceId())
                .containsExactly("CONJECTURE", "integration-conjecture");
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
    }
}
