package com.emergentterrain.research.api;

import com.emergentterrain.EmergentTerrainApplication;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.time.Instant;
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
    @Autowired private ObjectMapper objectMapper;

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

    @Test
    void exactFrozenQueueSnapshotReplaysPersistOneReservedHandoff() {
        ResearchWorkRequest request = requestWithCandidate(6L);
        ResearchConjectureQueueSnapshot selected = promotedSnapshot("snapshot-conjecture");

        coordinator.submitFrozenQueueSnapshot(request, selected);
        coordinator.submitFrozenQueueSnapshot(request, selected);

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId()).isEqualTo("snapshot-conjecture");
    }

    @Test
    void selectedThenMissedSnapshotReplayPreservesTheOriginalReservedHandoff() {
        ResearchWorkRequest request = requestWithCandidate(7L);
        coordinator.submitFrozenQueueSnapshot(request, promotedSnapshot("selected-conjecture"));

        assertThatThrownBy(() -> coordinator.submitFrozenQueueSnapshot(request, queueMissSnapshot()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound to a different research work payload");

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId()).isEqualTo("selected-conjecture");
    }

    @Test
    void exactFrozenQueueMissReplaysPersistOneUnreservedHandoff() {
        ResearchWorkRequest request = requestWithCandidate(8L);
        ResearchConjectureQueueSnapshot missed = queueMissSnapshot();

        coordinator.submitFrozenQueueSnapshot(request, missed);
        coordinator.submitFrozenQueueSnapshot(request, missed);

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isZero();
    }

    @Test
    void missedThenSelectedSnapshotReplayRollsBackTheNewReservation() {
        ResearchWorkRequest request = requestWithCandidate(9L);
        coordinator.submitFrozenQueueSnapshot(request, queueMissSnapshot());

        assertThatThrownBy(() -> coordinator.submitFrozenQueueSnapshot(request, promotedSnapshot("late-conjecture")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebound to a different research work payload");

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isZero();
    }

    @Test
    void concurrentExactSelectedSnapshotReplaysCommitOneReservedHandoff() throws Exception {
        ResearchWorkRequest request = requestWithCandidate(10L);
        ResearchConjectureQueueSnapshot selected = promotedSnapshot("concurrent-snapshot-conjecture");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(snapshotReplay(request, selected, ready, start));
            Future<Throwable> second = executor.submit(snapshotReplay(request, selected, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(request.requestId())).isPresent().get()
                .extracting(entry -> entry.getSourceId()).isEqualTo("concurrent-snapshot-conjecture");
    }

    @Test
    void concurrentSelectedAndMissedSnapshotsCommitOnlyOneCoherentOutcome() throws Exception {
        ResearchWorkRequest request = requestWithCandidate(11L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> selected = executor.submit(snapshotReplay(request, promotedSnapshot("race-conjecture"),
                    ready, start));
            Future<Throwable> missed = executor.submit(snapshotReplay(request, queueMissSnapshot(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(
                    selected.get(10, TimeUnit.SECONDS), missed.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull)
                    .allSatisfy(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("cannot be rebound"));
        } finally {
            executor.shutdownNow();
        }

        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reservations.count()).isIn(0L, 1L);
        if (reservations.count() == 1) {
            assertThat(reservations.findById(request.requestId())).isPresent().get()
                    .extracting(entry -> entry.getSourceId()).isEqualTo("race-conjecture");
        }
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

    private Callable<Throwable> snapshotReplay(ResearchWorkRequest request, ResearchConjectureQueueSnapshot snapshot,
                                                CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent snapshot replay test did not receive its start signal");
            }
            try {
                coordinator.submitFrozenQueueSnapshot(request, snapshot);
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

    private ResearchWorkRequest requestWithCandidate(long sourceRevision) {
        UUID subjectId = UUID.randomUUID();
        var context = objectMapper.createObjectNode();
        context.set("draftSelection", objectMapper.valueToTree(new ResearchDraftSelectionInput(
                "legacy-standard-catalog-v1", sourceRevision, "WEIGHTED", List.of(new ResearchDraftCandidate(
                subjectId, "PRIMARY", 1.0, "MATHEMATICS", List.of(new ResearchTopicOption("topic", 1.0)))))));
        return new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", sourceRevision,
                Instant.now(), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
    }

    private static ResearchConjectureQueueSnapshot promotedSnapshot(String sourceId) {
        return new ResearchConjectureQueueSnapshot(ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1,
                "math", 1.0, List.of(new ResearchConjectureSpecification("legacy-conjecture-v1", sourceId,
                "math", "BOUND", "lhs", "rhs", null, 1, 0, 0, 1, 0.1, null, "PROMOTED_ANOMALY")), List.of());
    }

    private static ResearchConjectureQueueSnapshot queueMissSnapshot() {
        return new ResearchConjectureQueueSnapshot(ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1,
                "math", Double.MIN_VALUE, List.of(), List.of(new ResearchConjectureSpecification(
                "legacy-conjecture-v1", "missed-conjecture", "math", "BOUND", "lhs", "rhs", null,
                1, 0, 0, 1, 0.1, null, "QUEUE_CANDIDATE")));
    }
}
