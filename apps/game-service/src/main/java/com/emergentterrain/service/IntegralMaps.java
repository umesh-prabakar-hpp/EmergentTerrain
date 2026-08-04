package com.emergentterrain.service;

/**
 * Phase 2.5 carve: 2D summed-area tables (one per tile-share kind) over a
 * room's tile grid. Promoted from a private nested class on {@link RoomService}
 * so {@link MapIndexService} can construct and consume it without depending on
 * a RoomService inner class.
 *
 * Each {@code int[][]} is sized {@code (h+1) × (w+1)} with row/col 0 used as
 * a sentinel zero-row, so {@link MapIndexService#rectSum} can compute the sum
 * of any axis-aligned rectangle in O(1) regardless of size.
 *
 * Field shape preserved verbatim from the original nested class so call sites
 * continue to read fields directly via {@code integrals.forest} etc.
 */
class IntegralMaps {

    final int[][] forest;
    final int[][] water;
    final int[][] clear;
    final int[][] industry;
    final int[][] road;

    IntegralMaps(int[][] forest, int[][] water, int[][] clear, int[][] industry, int[][] road) {
        this.forest   = forest;
        this.water    = water;
        this.clear    = clear;
        this.industry = industry;
        this.road     = road;
    }
}
