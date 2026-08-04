package com.emergentterrain.service;

/**
 * Phase 2.5 carve: one entry in {@link CachedMap}'s coarse-grid sample of a
 * room's tile-share landscape. Each cell is anchored at {@code (x, y)} in tile
 * coordinates and carries the average tile-share within a viewport-sized
 * window centred on that anchor.
 *
 * Promoted from a private nested class on {@link RoomService} so the broader
 * Phase 2.5 carve can hand it across service boundaries (specifically to
 * {@link BotMovementService#findBestProfileTarget}, which scans these cells
 * to pick a relocation target). Field shape preserved verbatim so call sites
 * continue to read {@code cell.x}, {@code cell.forest} etc. directly.
 */
class CoarseCell {

    final int    x;
    final int    y;
    final double forest;
    final double water;
    final double clear;
    final double industry;
    final double road;

    CoarseCell(int x, int y, double forest, double water, double clear, double industry, double road) {
        this.x        = x;
        this.y        = y;
        this.forest   = forest;
        this.water    = water;
        this.clear    = clear;
        this.industry = industry;
        this.road     = road;
    }
}
