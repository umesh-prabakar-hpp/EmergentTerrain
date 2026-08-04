package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;

/**
 * Phase 2.5 carve: copies dirty {@link CachedRoomState} fields back to the
 * persisted {@link RoomState} entity and writes via the JPA repository. Lifted
 * out of {@link RoomService} so the cache→state mapping has its own service
 * with its own tests, and so future cache changes don't churn RoomService.
 *
 * Scope is intentionally narrow: this is the <em>write</em> side only. The
 * <em>read</em> side ({@code getCachedRoomState}, {@code updateCacheFromState},
 * {@code fromJsonTiles}, {@code fromJsonHighways}, {@code readOccupancyJson},
 * {@code computeTileCounts}) stays in {@code RoomService} because those
 * deserializers and tile-counts helper are used by 4–6 non-cache callsites
 * (visual responses, GPU adapter, snapshot endpoints) and dragging them along
 * would be a larger churn than the carve buys back.
 *
 * The flush <em>scheduler</em> ({@code flushRoomStateCache} + the @Scheduled
 * lock-management code) also stays in {@code RoomService} because it owns the
 * shared {@code proposalLocks} infrastructure used by the proposal-vote path.
 *
 * Pure refactor: defaults match the inline values bound from
 * {@code application.yml} so existing rooms behave identically after the carve.
 */
@Service
public class RoomStateOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RoomStateOrchestrator.class);

    private final RoomStateRepository stateRepository;
    private final ObjectMapper        objectMapper;
    private final int                 flushMinChanges;
    private final long                flushMaxIntervalMs;

    public RoomStateOrchestrator(
            RoomStateRepository stateRepository,
            ObjectMapper objectMapper,
            @Value("${app.world.flushMinChanges:1}")     int flushMinChanges,
            @Value("${app.world.flushMaxIntervalMs:30000}") long flushMaxIntervalMs) {
        this.stateRepository    = stateRepository;
        this.objectMapper       = objectMapper;
        this.flushMinChanges    = Math.max(1, flushMinChanges);
        this.flushMaxIntervalMs = Math.max(250L, flushMaxIntervalMs);
    }

    public int  flushMinChanges()    { return flushMinChanges; }
    public long flushMaxIntervalMs() { return flushMaxIntervalMs; }

    /**
     * Flush a dirty cache entry to the persisted {@link RoomState}.
     *
     * <p>Returns {@code false} (no-op) in three cases:
     * <ol>
     *   <li>{@code cached} is null or not dirty.</li>
     *   <li>The cache has no recorded changes since the last flush AND the tile
     *       hash is unchanged from the previous flush — clears the dirty flag
     *       so the entry is not re-considered next tick.</li>
     *   <li>The pending change count is below {@link #flushMinChanges} and the
     *       last flush was within {@link #flushMaxIntervalMs} (i.e. the entry
     *       is not yet overdue).</li>
     * </ol>
     *
     * <p>Returns {@code true} after a successful write, having reset
     * {@code cached.dirty}, {@code cached.lastFlushAt}, and
     * {@code cached.changesSinceFlush}.
     */
    public boolean persistIfDirty(Room room, RoomState state, CachedRoomState cached) {
        if (cached == null || !cached.dirty) {
            return false;
        }
        Instant now = Instant.now();
        int changesSinceFlush = cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush;
        boolean overdue = cached.lastFlushAt == null
                || now.toEpochMilli() - cached.lastFlushAt.toEpochMilli() >= flushMaxIntervalMs;
        boolean noMaterialChange = changesSinceFlush <= 0
                && Objects.equals(cached.stateHash, cached.previousStateHash);
        if (noMaterialChange) {
            cached.dirty = false;
            cached.lastFlushAt = now;
            cached.changesSinceFlush = 0;
            return false;
        }
        if (changesSinceFlush < flushMinChanges && !overdue) {
            return false;
        }
        state.setTilesJson(toJson(cached.tiles));
        state.setHighwaysJson(toJson(cached.highways));
        state.setRoadPressureJson(toJson(cached.roadPressure));
        state.setSharedTokensJson(cached.sharedTokensJson);
        state.setSharedQuestsJson(cached.sharedQuestsJson);
        state.setMemoryMarkersJson(cached.memoryMarkersJson);
        state.setGovernmentStateJson(cached.governmentStateJson);
        if (cached.occupancy != null) {
            state.setOccupancyJson(toJson(cached.occupancy));
            state.setOccupancyUpdatedAt(cached.occupancyUpdatedAt);
        }
        state.setPreviousStateHash(cached.previousStateHash);
        state.setStateHash(cached.stateHash == null ? hashTiles(cached.tiles) : cached.stateHash);
        state.setTotalChangeCount(cached.totalChangeCount == null ? 0 : cached.totalChangeCount);
        state.setChangesSinceFlush(changesSinceFlush);
        state.setTileClearCount(cached.tileClearCount);
        state.setTileForestCount(cached.tileForestCount);
        state.setTileWaterCount(cached.tileWaterCount);
        state.setTileIndustryCount(cached.tileIndustryCount);
        state.setTileHighwayCount(cached.tileHighwayCount);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        cached.dirty = false;
        cached.lastFlushAt = now;
        cached.changesSinceFlush = 0;
        log.debug("roomStateFlush roomId={} updatedAt={} stateHash={}",
                room.getId(), now, state.getStateHash());
        return true;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize", e);
        }
    }

    private String hashTiles(int[][] tiles) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(tiles);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tiles", e);
        }
    }
}
