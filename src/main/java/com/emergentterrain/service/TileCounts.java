package com.emergentterrain.service;

/**
 * Phase 2.5 carve: count of each tile kind in a room snapshot. Lifted out of
 * {@link RoomService} as a top-level package-private class so it can be passed
 * across service boundaries during the broader carve without depending on a
 * {@code RoomService} inner class.
 *
 * Shape preserved verbatim from the original nested class — including the
 * package-private final fields — so callers continue to read
 * {@code counts.clear} / {@code counts.forest} etc. without churn.
 */
class TileCounts {

    final int clear;
    final int forest;
    final int water;
    final int industry;
    final int road;

    TileCounts(int clear, int forest, int water, int industry, int road) {
        this.clear    = clear;
        this.forest   = forest;
        this.water    = water;
        this.industry = industry;
        this.road     = road;
    }
}
