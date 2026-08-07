package com.emergentterrain.research.api;

import com.emergentterrain.EmergentTerrainApplication;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the frozen conjecture handoff uses one durable game-owned reservation/outbox pair. */
@SpringBootTest(classes = EmergentTerrainApplication.class)
@ActiveProfiles("test")
class FrozenResearchWorkSubmissionPersistenceTest {
    @Autowired private GameResearchWorkMapper mapper;
    @Autowired private FrozenResearchWorkSubmissionCoordinator coordinator;
    @Autowired private ResearchWorkOutboxService outboxService;
    @Autowired private ResearchWorkOutboxRepository outbox;
    @Autowired private ResearchWorkSourceReservationRepository reservations;

    @BeforeEach
    void clearHandoffTables() {
        outbox.deleteAll();
        reservations.deleteAll();
    }

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

    @Test
    void conflictingReplayPreservesTheOriginalCommittedHandoff() {
        UUID roomId = UUID.randomUUID();
        ResearchWorkRequest request = mapper.fromTick(roomId, List.of(), 2L, null);
        ResearchConjectureQueueSelection original = selection("original-conjecture");

        coordinator.submitFrozenConjecture(request, original);

        assertThatThrownBy(() -> coordinator.submitFrozenConjecture(request, selection("conflicting-conjecture")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound");

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId()).isEqualTo("original-conjecture");
    }

    @Test
    void outboxPayloadConflictRollsBackTheNewSourceReservation() {
        UUID roomId = UUID.randomUUID();
        ResearchWorkRequest original = mapper.fromTick(roomId, List.of(), 3L, null);
        outboxService.enqueue(original);

        assertThatThrownBy(() -> coordinator.submitFrozenConjecture(original, selection("new-conjecture")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound to a different research work payload");

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isZero();
        assertThat(outbox.findById(original.requestId())).isPresent();
    }

    @Test
    void concurrentMatchingReplaysCommitOnlyOneReservationAndOutboxPair() throws Exception {
        UUID roomId = UUID.randomUUID();
        ResearchWorkRequest request = mapper.fromTick(roomId, List.of(), 4L, null);
        ResearchConjectureQueueSelection selection = selection("concurrent-conjecture");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> replay = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent replay test did not receive its start signal");
            }
            coordinator.submitFrozenConjecture(request, selection);
            return null;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = executor.submit(replay);
            Future<Void> second = executor.submit(replay);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId()).isEqualTo("concurrent-conjecture");
    }

    @Test
    void concurrentConflictingReplaysLeaveOneCommittedHandoff() throws Exception {
        UUID roomId = UUID.randomUUID();
        ResearchWorkRequest request = mapper.fromTick(roomId, List.of(), 5L, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Throwable> firstReplay = conflictingReplay(request, selection("concurrent-first"), ready, start);
        Callable<Throwable> secondReplay = conflictingReplay(request, selection("concurrent-second"), ready, start);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(firstReplay);
            Future<Throwable> second = executor.submit(secondReplay);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allSatisfy(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("cannot be rebound"));
        } finally {
            executor.shutdownNow();
        }

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId())
                .isIn("concurrent-first", "concurrent-second");
    }

    private Callable<Throwable> conflictingReplay(ResearchWorkRequest request,
                                                   ResearchConjectureQueueSelection selection,
                                                   CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent replay test did not receive its start signal");
            }
            try {
                coordinator.submitFrozenConjecture(request, selection);
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        };
    }

    private static ResearchConjectureQueueSelection selection(String sourceId) {
        return new ResearchConjectureQueueSelection(new ResearchConjectureSpecification(
                "legacy-conjecture-v1", sourceId, "math", "BOUND", "lhs", "rhs", null,
                1, 0, 0, 1, 0.1, null, "QUEUE_CANDIDATE"), 1);
    }
}
