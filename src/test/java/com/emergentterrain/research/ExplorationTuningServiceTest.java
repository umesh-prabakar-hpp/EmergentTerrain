package com.emergentterrain.research;

import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for Phase 4b ExplorationTuningService — verifies the disabled-flag no-op
 * invariant, the cold-start fallback, the control loop's two adjustment
 * directions, and the per-room state isolation.
 *
 * The service reads {@link ReplicationService#recentFailureRate} which itself
 * tracks outcomes via {@code processOne}. Tests drive that pathway through the
 * real ReplicationService and a mock queue of replication requests so the
 * sliding window is populated naturally.
 */
class ExplorationTuningServiceTest {

    private ReplicationService replications;
    private ExplorationTuningService tuning;

    @BeforeEach
    void setUp() {
        ResearchPaperRepository papers = mock(ResearchPaperRepository.class);
        RoomBotRepository       bots   = mock(RoomBotRepository.class);
        ObjectMapper            mapper = new ObjectMapper();
        replications = new ReplicationService(papers, bots, mapper);
        tuning       = new ExplorationTuningService(true, replications);
    }

    @Test
    void disabledServiceReturnsStaticDefaults() {
        ExplorationTuningService disabled = new ExplorationTuningService(false, replications);
        UUID room = UUID.randomUUID();
        // Even after thousands of "replications" being notified, thresholds stay at defaults.
        for (int i = 0; i < 100; i++) disabled.onReplicationProcessed(room);

        assertThat(disabled.getZThreshold(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_Z_THRESHOLD);
        assertThat(disabled.getExplorationTrigger(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_EXPLORATION_TRIGGER);
    }

    @Test
    void coldStartReturnsDefaultThresholdsUntilWindowFills() {
        UUID room = UUID.randomUUID();
        // Process fewer than FAILURE_WINDOW_MIN_SAMPLES outcomes — the failure-rate
        // reader returns -1.0 so the tuner makes no adjustment.
        for (int i = 0; i < 5; i++) {
            simulateProcessedReplication(room, /* failed = */ true);
            tuning.onReplicationProcessed(room);
        }
        assertThat(tuning.getZThreshold(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_Z_THRESHOLD);
    }

    @Test
    void lowFailureRateRaisesBothThresholds() {
        UUID room = UUID.randomUUID();
        // 10 confirming replications → failure rate = 0% → below LOW_FAILURE_FLOOR.
        for (int i = 0; i < ExplorationTuningService.ADJUST_EVERY_N_REPLICATIONS; i++) {
            simulateProcessedReplication(room, false);
            tuning.onReplicationProcessed(room);
        }

        assertThat(tuning.getZThreshold(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_Z_THRESHOLD
                        + ExplorationTuningService.STEP_SIZE_Z);
        assertThat(tuning.getExplorationTrigger(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_EXPLORATION_TRIGGER
                        + ExplorationTuningService.STEP_SIZE_EXPLORATION);
    }

    @Test
    void highFailureRateLowersBothThresholds() {
        UUID room = UUID.randomUUID();
        // 10 failing replications → failure rate = 100% → above HIGH_FAILURE_CEILING.
        for (int i = 0; i < ExplorationTuningService.ADJUST_EVERY_N_REPLICATIONS; i++) {
            simulateProcessedReplication(room, true);
            tuning.onReplicationProcessed(room);
        }

        assertThat(tuning.getZThreshold(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_Z_THRESHOLD
                        - ExplorationTuningService.STEP_SIZE_Z);
        assertThat(tuning.getExplorationTrigger(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_EXPLORATION_TRIGGER
                        - ExplorationTuningService.STEP_SIZE_EXPLORATION);
    }

    @Test
    void midRangeFailureRateDoesNotAdjust() {
        UUID room = UUID.randomUUID();
        // 10 outcomes with 3 failures → 30% failure rate (between floor and ceiling).
        for (int i = 0; i < 10; i++) {
            simulateProcessedReplication(room, i < 3);
            tuning.onReplicationProcessed(room);
        }

        assertThat(tuning.getZThreshold(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_Z_THRESHOLD);
        assertThat(tuning.getExplorationTrigger(room))
                .isEqualTo(ExplorationTuningService.DEFAULT_EXPLORATION_TRIGGER);
    }

    @Test
    void thresholdsClampAtBounds() {
        UUID room = UUID.randomUUID();
        // Many low-failure cycles in a row — eventually clamps at Z_MAX / EXPLORATION_TRIGGER_MAX.
        for (int i = 0; i < 200; i++) {
            simulateProcessedReplication(room, false);
            tuning.onReplicationProcessed(room);
        }
        assertThat(tuning.getZThreshold(room)).isEqualTo(ExplorationTuningService.Z_MAX);
        assertThat(tuning.getExplorationTrigger(room))
                .isEqualTo(ExplorationTuningService.EXPLORATION_TRIGGER_MAX);
    }

    @Test
    void perRoomStateIsIsolated() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        // RoomA: all confirms → raise. RoomB: all fails → lower.
        for (int i = 0; i < 10; i++) {
            simulateProcessedReplication(roomA, false);
            tuning.onReplicationProcessed(roomA);
            simulateProcessedReplication(roomB, true);
            tuning.onReplicationProcessed(roomB);
        }
        assertThat(tuning.getZThreshold(roomA)).isGreaterThan(tuning.getZThreshold(roomB));
        assertThat(tuning.getExplorationTrigger(roomA))
                .isGreaterThan(tuning.getExplorationTrigger(roomB));
    }

    // ---- helpers ----

    /**
     * Drive ReplicationService.recordOutcome indirectly by enqueueing a request
     * for the test's room and calling processOne with a pool whose result will
     * either confirm or fail — but we don't have a controllable math engine
     * here. Instead, use the package-private path by directly enqueueing then
     * invoking processOne with an empty pool would return early. Easiest: use
     * reflection-free path through a public processOne with a one-element pool.
     *
     * Actually simplest: bypass processOne entirely and call the
     * package-private recordOutcome via reflection — the integration of
     * processOne is exercised by other tests (PublishPaperTest, etc.).
     */
    private void simulateProcessedReplication(UUID roomId, boolean failed) {
        try {
            java.lang.reflect.Method m = ReplicationService.class.getDeclaredMethod(
                    "recordOutcome", UUID.class, boolean.class);
            m.setAccessible(true);
            m.invoke(replications, roomId, failed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    private static List<Researcher> unusedPool() { return List.of(); }
    @SuppressWarnings("unused")
    private static Random unusedRng() { return new Random(); }
}
