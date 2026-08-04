package com.emergentterrain.service;

/**
 * Per-room mirror of the world snapshot last sent to the GPU engine. Lets
 * {@link BotSimulationGpuService} send {@code tile_edits} deltas instead of
 * the full {@code tiles} / {@code highways} grids on each
 * {@code SimulateBotsRequest}, when {@code app.gpu.bots.cacheWorld} is on
 * and the snapshot hasn't drifted past the configured edit budget.
 *
 * Phase 2.5 carve: promoted from a private nested class on
 * {@link RoomService} so the new {@link BotSimulationGpuService} can own
 * the cache map without depending on a RoomService inner class. Field
 * shape and access preserved verbatim — the worldId is generated once per
 * (room, cache-rebuild) cycle so the GPU engine can detect cache misses.
 */
class GpuWorldCache {

    final String worldId;
    int[][] tiles;
    boolean[][] highways;

    GpuWorldCache(String worldId) {
        this.worldId = worldId;
    }
}
