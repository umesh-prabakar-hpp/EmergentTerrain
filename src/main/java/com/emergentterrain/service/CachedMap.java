package com.emergentterrain.service;

import java.util.List;

/**
 * Phase 2.5 carve: per-room coarse-grid landscape index built once per
 * (tiles, highways) snapshot. Combines the integral-image arrays for O(1)
 * rectangle sums with a stride-sampled list of {@link CoarseCell}s over the
 * grid.
 *
 * The {@code key} is a content hash of the (tiles, highways) snapshot the
 * cache entry was built from; {@link MapIndexService#getCachedMap} compares
 * keys before reusing a cached entry, so a rebuild fires when the world
 * changes underneath. {@code viewW}, {@code viewH}, and {@code step} record
 * the sampling parameters so consumers (like
 * {@link BotMovementService#findBestProfileTarget}) can refine results
 * around a chosen cell with the same window dimensions.
 *
 * Promoted from a private nested class on {@link RoomService}; field shape
 * preserved so existing read sites compile unchanged.
 */
class CachedMap {

    final String           key;
    final IntegralMaps     integrals;
    final List<CoarseCell> cells;
    final int              viewW;
    final int              viewH;
    final int              step;

    CachedMap(String key, IntegralMaps integrals, List<CoarseCell> cells, int viewW, int viewH, int step) {
        this.key       = key;
        this.integrals = integrals;
        this.cells     = cells;
        this.viewW     = viewW;
        this.viewH     = viewH;
        this.step      = step;
    }
}
