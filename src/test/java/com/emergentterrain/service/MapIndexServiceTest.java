package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of {@link MapIndexService}: per-room coarse-grid index
 * with content-hashed reuse, integral-map summed-area tables built from a
 * (tiles, highways) snapshot, and O(1) rectangle sums.
 */
class MapIndexServiceTest {

    private MapIndexService svc;

    @BeforeEach
    void setUp() {
        svc = new MapIndexService(new ObjectMapper().findAndRegisterModules());
    }

    // ---- rectSum ----

    @Test
    void rectSumOnTrivialIntegralReturnsExpectedAreaSum() {
        // Build a 3×3 integral over a tile grid where every cell is "forest" (1).
        // Rectangle (0,0)-(3,3) covers the whole 3×3 area → sum 9.
        // The integral is sized (h+1)×(w+1) per the buildIntegrals contract.
        int[][] integral = new int[][]{
                {0, 0, 0, 0},
                {0, 1, 2, 3},
                {0, 2, 4, 6},
                {0, 3, 6, 9}};

        assertThat(svc.rectSum(integral, 0, 0, 3, 3)).isEqualTo(9);
        // Corner sub-rectangle (0,0)-(2,2) → sum 4.
        assertThat(svc.rectSum(integral, 0, 0, 2, 2)).isEqualTo(4);
        // Right edge (1,0)-(3,3) → sum 6.
        assertThat(svc.rectSum(integral, 1, 0, 3, 3)).isEqualTo(6);
    }

    // ---- getCachedMap ----

    @Test
    void getCachedMapBuildsIntegralsAndCellsFromSnapshot() {
        Room room = roomWithId(UUID.randomUUID());
        int[][] tiles = uniformTiles(20, 20, 0); // all clear
        boolean[][] highways = new boolean[20][20];

        CachedMap cached = svc.getCachedMap(room, tiles, highways);

        assertThat(cached).isNotNull();
        assertThat(cached.integrals).isNotNull();
        // Sentinel row/col present.
        assertThat(cached.integrals.clear).hasDimensions(21, 21);
        // The whole board is "clear" (type 0). Sum over the full grid = 400.
        assertThat(svc.rectSum(cached.integrals.clear, 0, 0, 20, 20)).isEqualTo(400);
        // All other tile-share integrals should be zero across the whole board.
        assertThat(svc.rectSum(cached.integrals.forest, 0, 0, 20, 20)).isZero();
        assertThat(svc.rectSum(cached.integrals.water, 0, 0, 20, 20)).isZero();
        assertThat(svc.rectSum(cached.integrals.industry, 0, 0, 20, 20)).isZero();
        assertThat(svc.rectSum(cached.integrals.road, 0, 0, 20, 20)).isZero();
        // Cells are populated.
        assertThat(cached.cells).isNotEmpty();
        // viewW, viewH clamp to the smaller of (board, viewport-constant).
        assertThat(cached.viewW).isEqualTo(20);
        assertThat(cached.viewH).isEqualTo(20);
    }

    @Test
    void getCachedMapReusesEntryWhenSnapshotUnchanged() {
        Room room = roomWithId(UUID.randomUUID());
        int[][] tiles = uniformTiles(8, 8, 1);
        boolean[][] highways = new boolean[8][8];

        CachedMap first  = svc.getCachedMap(room, tiles, highways);
        CachedMap second = svc.getCachedMap(room, tiles, highways);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getCachedMapRebuildsWhenTilesChange() {
        Room room = roomWithId(UUID.randomUUID());
        int[][] tilesA = uniformTiles(8, 8, 1);
        int[][] tilesB = uniformTiles(8, 8, 2);
        boolean[][] highways = new boolean[8][8];

        CachedMap first  = svc.getCachedMap(room, tilesA, highways);
        CachedMap second = svc.getCachedMap(room, tilesB, highways);

        assertThat(second).isNotSameAs(first);
        assertThat(second.key).isNotEqualTo(first.key);
    }

    @Test
    void getCachedMapRebuildsWhenHighwaysChange() {
        Room room = roomWithId(UUID.randomUUID());
        int[][] tiles = uniformTiles(8, 8, 0);
        boolean[][] highwaysA = new boolean[8][8];
        boolean[][] highwaysB = new boolean[8][8];
        highwaysB[3][4] = true;

        CachedMap first  = svc.getCachedMap(room, tiles, highwaysA);
        CachedMap second = svc.getCachedMap(room, tiles, highwaysB);

        assertThat(second).isNotSameAs(first);
        assertThat(second.key).isNotEqualTo(first.key);
        // Highway road-share moved from 0 to 1 in the second build.
        assertThat(svc.rectSum(second.integrals.road, 0, 0, 8, 8)).isEqualTo(1);
        assertThat(svc.rectSum(first.integrals.road,  0, 0, 8, 8)).isZero();
    }

    @Test
    void integralsCorrectlyCountMixedTileTypes() {
        Room room = roomWithId(UUID.randomUUID());
        // 4×4 grid: top half forest (1), bottom half water (2), one industry tile.
        int[][] tiles = new int[4][4];
        for (int x = 0; x < 4; x++) {
            tiles[0][x] = 1;
            tiles[1][x] = 1;
            tiles[2][x] = 2;
            tiles[3][x] = 2;
        }
        tiles[3][3] = 3; // industry
        boolean[][] highways = new boolean[4][4];

        CachedMap cached = svc.getCachedMap(room, tiles, highways);

        assertThat(svc.rectSum(cached.integrals.forest,   0, 0, 4, 4)).isEqualTo(8);
        assertThat(svc.rectSum(cached.integrals.water,    0, 0, 4, 4)).isEqualTo(7);
        assertThat(svc.rectSum(cached.integrals.industry, 0, 0, 4, 4)).isEqualTo(1);
        assertThat(svc.rectSum(cached.integrals.clear,    0, 0, 4, 4)).isZero();
    }

    private static Room roomWithId(UUID id) {
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(id);
        return room;
    }

    private static int[][] uniformTiles(int height, int width, int type) {
        int[][] tiles = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = type;
            }
        }
        return tiles;
    }
}
