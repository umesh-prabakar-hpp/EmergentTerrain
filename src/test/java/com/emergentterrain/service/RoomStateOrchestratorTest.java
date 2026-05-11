package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of {@link RoomStateOrchestrator#persistIfDirty} so the
 * Phase 2.5 carve out of {@link RoomService} cannot regress. Covers every
 * branch in the persist decision: not-dirty no-op, no-material-change
 * fast-clear, below-min-changes-and-not-overdue skip, and the live-write path.
 */
class RoomStateOrchestratorTest {

    private RoomStateRepository  stateRepository;
    private RoomStateOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stateRepository = mock(RoomStateRepository.class);
        orchestrator = new RoomStateOrchestrator(
                stateRepository,
                new ObjectMapper().findAndRegisterModules(),
                /* flushMinChanges */    1,
                /* flushMaxIntervalMs */ 30_000L);
    }

    @Test
    void noOpWhenCacheIsNull() {
        Room room = roomWithId(UUID.randomUUID());

        boolean wrote = orchestrator.persistIfDirty(room, new RoomState(), null);

        assertThat(wrote).isFalse();
        verify(stateRepository, never()).save(any());
    }

    @Test
    void noOpWhenCacheIsNotDirty() {
        Room room = roomWithId(UUID.randomUUID());
        CachedRoomState cached = freshCache();
        cached.dirty = false;

        boolean wrote = orchestrator.persistIfDirty(room, new RoomState(), cached);

        assertThat(wrote).isFalse();
        verify(stateRepository, never()).save(any());
    }

    @Test
    void clearsDirtyAndSkipsWriteWhenNoMaterialChange() {
        // Dirty flag set but changesSinceFlush == 0 and stateHash == previousStateHash.
        // The persist path should reset the flush bookkeeping without touching the DB.
        Room room = roomWithId(UUID.randomUUID());
        CachedRoomState cached = freshCache();
        cached.dirty = true;
        cached.changesSinceFlush = 0;
        cached.stateHash = "h1";
        cached.previousStateHash = "h1";

        boolean wrote = orchestrator.persistIfDirty(room, new RoomState(), cached);

        assertThat(wrote).isFalse();
        assertThat(cached.dirty).isFalse();
        assertThat(cached.changesSinceFlush).isZero();
        assertThat(cached.lastFlushAt).isNotNull();
        verify(stateRepository, never()).save(any());
    }

    @Test
    void skipsWriteWhenBelowMinChangesAndNotOverdue() {
        // Stricter threshold so 1 change isn't enough.
        RoomStateOrchestrator strict = new RoomStateOrchestrator(
                stateRepository, new ObjectMapper().findAndRegisterModules(),
                /* flushMinChanges */    5,
                /* flushMaxIntervalMs */ 60_000L);
        Room room = roomWithId(UUID.randomUUID());
        CachedRoomState cached = freshCache();
        cached.dirty = true;
        cached.changesSinceFlush = 2;             // < 5
        cached.stateHash = "h2";
        cached.previousStateHash = "h1";          // material change exists
        cached.lastFlushAt = Instant.now();       // not overdue

        boolean wrote = strict.persistIfDirty(room, new RoomState(), cached);

        assertThat(wrote).isFalse();
        // Below threshold ≠ no material change, so dirty stays true for next tick.
        assertThat(cached.dirty).isTrue();
        verify(stateRepository, never()).save(any());
    }

    @Test
    void writesWhenChangesMeetThreshold() {
        Room room = roomWithId(UUID.randomUUID());
        RoomState state = new RoomState();
        CachedRoomState cached = freshCache();
        cached.dirty = true;
        cached.changesSinceFlush = 3;
        cached.stateHash = "h3";
        cached.previousStateHash = "h2";

        boolean wrote = orchestrator.persistIfDirty(room, state, cached);

        assertThat(wrote).isTrue();
        // Cache flush bookkeeping reset.
        assertThat(cached.dirty).isFalse();
        assertThat(cached.changesSinceFlush).isZero();
        assertThat(cached.lastFlushAt).isNotNull();
        // State copied from cache into the JPA entity.
        assertThat(state.getStateHash()).isEqualTo("h3");
        assertThat(state.getPreviousStateHash()).isEqualTo("h2");
        assertThat(state.getTileClearCount()).isEqualTo(cached.tileClearCount);
        assertThat(state.getTileForestCount()).isEqualTo(cached.tileForestCount);
        ArgumentCaptor<RoomState> captor = ArgumentCaptor.forClass(RoomState.class);
        verify(stateRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(state);
    }

    @Test
    void writesWhenOverdueEvenIfBelowMinChanges() {
        RoomStateOrchestrator strict = new RoomStateOrchestrator(
                stateRepository, new ObjectMapper().findAndRegisterModules(),
                /* flushMinChanges */    5,
                /* flushMaxIntervalMs */ 1L);   // ~always overdue
        Room room = roomWithId(UUID.randomUUID());
        RoomState state = new RoomState();
        CachedRoomState cached = freshCache();
        cached.dirty = true;
        cached.changesSinceFlush = 1;
        cached.stateHash = "hX";
        cached.previousStateHash = "hY";
        cached.lastFlushAt = Instant.now().minusSeconds(60);  // older than 1 ms

        boolean wrote = strict.persistIfDirty(room, state, cached);

        assertThat(wrote).isTrue();
        verify(stateRepository).save(state);
    }

    @Test
    void hashesTilesWhenCachedHashIsNull() {
        Room room = roomWithId(UUID.randomUUID());
        RoomState state = new RoomState();
        CachedRoomState cached = freshCache();
        cached.dirty = true;
        cached.changesSinceFlush = 1;
        cached.stateHash = null;             // null triggers compute-from-tiles
        cached.previousStateHash = "prev";

        orchestrator.persistIfDirty(room, state, cached);

        // SHA-256 hex digest is 64 chars.
        assertThat(state.getStateHash()).isNotNull().hasSize(64);
    }

    @Test
    void tunableAccessorsReturnConfiguredValues() {
        assertThat(orchestrator.flushMinChanges()).isEqualTo(1);
        assertThat(orchestrator.flushMaxIntervalMs()).isEqualTo(30_000L);
    }

    @Test
    void constructorClampsTunablesToFloors() {
        RoomStateOrchestrator clamped = new RoomStateOrchestrator(
                stateRepository, new ObjectMapper().findAndRegisterModules(),
                /* flushMinChanges */    0,    // clamps up to 1
                /* flushMaxIntervalMs */ 100); // clamps up to 250

        assertThat(clamped.flushMinChanges()).isEqualTo(1);
        assertThat(clamped.flushMaxIntervalMs()).isEqualTo(250L);
    }

    private static Room roomWithId(UUID id) {
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(id);
        return room;
    }

    /**
     * Build a CachedRoomState with the bare minimum to exercise persistIfDirty
     * — small tile/highway/pressure arrays so the JSON serializers have real
     * input but the assertions stay readable.
     */
    private static CachedRoomState freshCache() {
        int[][] tiles = new int[2][2];
        boolean[][] highways = new boolean[2][2];
        double[][] pressure = new double[2][2];
        TileCounts counts = new TileCounts(4, 0, 0, 0, 0);
        return new CachedRoomState(
                tiles, highways, pressure,
                "{}", "{}", "[]", "{}",
                /* stateHash */ null, /* previousStateHash */ null,
                counts,
                /* totalChangeCount */ 0L,
                /* changesSinceFlush */ 0,
                /* occupancy */ null,
                /* occupancyUpdatedAt */ null);
    }
}
