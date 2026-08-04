package com.emergentterrain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2.5 carve: per-room mutable cache of the deserialized board, traffic
 * pressure, occupancy snapshot, and persistence bookkeeping. Lifted out of
 * {@link RoomService} as a top-level package-private class so the broader
 * RoomService refactor can hand it to the next set of extracted services
 * (specifically the future {@code BotMovementService}, which needs to read
 * {@code tiles}, {@code highways}, {@code roadPressure} and write the
 * {@code dirty} flag without going through the room cache map).
 *
 * Field shape and visibility preserved verbatim from the original nested
 * class so call sites continue to read fields directly. This keeps the
 * carve a pure mechanical promotion — no behavioural change, no reformat
 * of the {@code RoomService} side.
 *
 * Mutability lives in the small fields ({@code stateHash}, the tile counts,
 * counters, the {@code dirty} flag, etc.); the array references are final
 * and the arrays themselves are mutated in place by passes like
 * {@link HighwayEmergenceService#evolve}.
 */
class CachedRoomState {

    final int[][]     tiles;
    final boolean[][] highways;
    final double[][]  roadPressure;
    String            sharedTokensJson;
    String            sharedQuestsJson;
    String            memoryMarkersJson;
    String            governmentStateJson;
    String            stateHash;
    String            previousStateHash;
    int               tileClearCount;
    int               tileForestCount;
    int               tileWaterCount;
    int               tileIndustryCount;
    int               tileHighwayCount;
    Long              totalChangeCount;
    Integer           changesSinceFlush;
    List<OccupantSnapshot> occupancy;
    Instant                occupancyUpdatedAt;
    boolean                dirty;
    Instant                lastFlushAt;

    CachedRoomState(int[][] tiles,
                    boolean[][] highways,
                    double[][] roadPressure,
                    String sharedTokensJson,
                    String sharedQuestsJson,
                    String memoryMarkersJson,
                    String governmentStateJson,
                    String stateHash,
                    String previousStateHash,
                    TileCounts counts,
                    Long totalChangeCount,
                    Integer changesSinceFlush,
                    List<OccupantSnapshot> occupancy,
                    Instant occupancyUpdatedAt) {
        this.tiles               = tiles;
        this.highways            = highways;
        this.roadPressure        = roadPressure;
        this.sharedTokensJson    = sharedTokensJson;
        this.sharedQuestsJson    = sharedQuestsJson;
        this.memoryMarkersJson   = memoryMarkersJson;
        this.governmentStateJson = governmentStateJson;
        this.stateHash           = stateHash;
        this.previousStateHash   = previousStateHash;
        this.tileClearCount      = counts.clear;
        this.tileForestCount     = counts.forest;
        this.tileWaterCount      = counts.water;
        this.tileIndustryCount   = counts.industry;
        this.tileHighwayCount    = counts.road;
        this.totalChangeCount    = totalChangeCount    == null ? 0 : totalChangeCount;
        this.changesSinceFlush   = changesSinceFlush   == null ? 0 : changesSinceFlush;
        this.occupancy           = occupancy           == null ? new ArrayList<>() : occupancy;
        this.occupancyUpdatedAt  = occupancyUpdatedAt;
        this.dirty               = false;
        this.lastFlushAt         = Instant.now();
    }
}
