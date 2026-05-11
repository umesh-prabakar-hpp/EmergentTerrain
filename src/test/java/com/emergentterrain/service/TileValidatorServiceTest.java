package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the behaviour of {@link TileValidatorService} so the Phase 2.5 carve
 * out of {@link RoomService} cannot regress. Expected values mirror the inline
 * implementation that lived in RoomService before this extraction.
 *
 * The community-rules tests use {@code adherence = 0.0} to force the
 * {@code Math.random() < gate} gate to always fail (gate = 0), so the
 * randomised composite predicate becomes deterministic and the test can pin
 * each branch without an RNG seed.
 */
class TileValidatorServiceTest {

    private final OccupancyService occupancy = new OccupancyService(null, null, null);
    private final TileValidatorService svc = new TileValidatorService(occupancy);

    // ---------- isAccessTile ----------

    @Test
    void isAccessTileTrueForClearAndRoad() {
        int[][] tiles = { {0, 4}, {1, 2} };
        boolean[][] highways = new boolean[0][0];

        assertThat(svc.isAccessTile(tiles, highways, 0, 0)).isTrue();   // clear
        assertThat(svc.isAccessTile(tiles, highways, 1, 0)).isTrue();   // road
        assertThat(svc.isAccessTile(tiles, highways, 0, 1)).isFalse();  // forest
        assertThat(svc.isAccessTile(tiles, highways, 1, 1)).isFalse();  // water
    }

    @Test
    void isAccessTileTrueWhenHighwayCoversNonAccessTile() {
        int[][] tiles = { {3, 3}, {3, 3} };                     // all industry
        boolean[][] highways = { {false, true}, {false, false} };

        assertThat(svc.isAccessTile(tiles, highways, 1, 0)).isTrue();   // highway overrides
        assertThat(svc.isAccessTile(tiles, highways, 0, 0)).isFalse();  // no highway, industry
    }

    @Test
    void isAccessTileEmptyHighwayArrayDoesNotBlockClear() {
        int[][] tiles = { {0} };
        boolean[][] empty = new boolean[0][0];

        assertThat(svc.isAccessTile(tiles, empty, 0, 0)).isTrue();
    }

    // ---------- hasAccessConnectivity ----------

    @Test
    void hasAccessConnectivityTrueWhenNoAccessTilesExist() {
        // All forest: no access tiles, ratio computation skipped, returns true.
        int[][] tiles = filled(10, 10, 1);
        boolean[][] highways = new boolean[10][10];

        assertThat(svc.hasAccessConnectivity(tiles, highways, 5, 5)).isTrue();
    }

    @Test
    void hasAccessConnectivityTrueWhenAllAccessTilesFormOneCluster() {
        // All clear: one giant component → ratio = 1.0 ≥ 0.25.
        int[][] tiles = filled(10, 10, 0);
        boolean[][] highways = new boolean[10][10];

        assertThat(svc.hasAccessConnectivity(tiles, highways, 5, 5)).isTrue();
    }

    @Test
    void hasAccessConnectivityFalseWhenLargestClusterUnder25Percent() {
        // 10×10 grid: forest background with an isolated clear tile at every
        // even (x,y). 25 access tiles total, each surrounded by forest under
        // 4-connectivity → 25 single-tile clusters → ratio 1/25 = 0.04 < 0.25.
        int[][] tiles = new int[10][10];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                tiles[y][x] = 1;
            }
        }
        for (int y = 0; y < 10; y += 2) {
            for (int x = 0; x < 10; x += 2) {
                tiles[y][x] = 0;
            }
        }
        boolean[][] highways = new boolean[10][10];

        assertThat(svc.hasAccessConnectivity(tiles, highways, 5, 5)).isFalse();
    }

    @Test
    void hasAccessConnectivityTrueWhenLargestClusterAtLeast25Percent() {
        // 10×10: left half clear (50 tiles, one cluster), right half forest with
        // a single stray clear in the corner (1 tile, one cluster).
        // Total access = 51, largest = 50, ratio ≈ 0.98 ≥ 0.25.
        int[][] tiles = new int[10][10];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                tiles[y][x] = (x < 5) ? 0 : 1;
            }
        }
        tiles[9][9] = 0;
        boolean[][] highways = new boolean[10][10];

        assertThat(svc.hasAccessConnectivity(tiles, highways, 5, 5)).isTrue();
    }

    // ---------- isRapidOscillation ----------

    @Test
    void isRapidOscillationFalseOnEmptyOrSingleTypedHistory() {
        assertThat(svc.isRapidOscillation(null)).isFalse();
        assertThat(svc.isRapidOscillation(Collections.emptyList())).isFalse();
        assertThat(svc.isRapidOscillation(Arrays.asList(1))).isFalse();
        assertThat(svc.isRapidOscillation(Arrays.asList(null, 1, null, null))).isFalse();
    }

    @Test
    void isRapidOscillationFalseWhenTwoMostRecentMatch() {
        assertThat(svc.isRapidOscillation(Arrays.asList(1, 1, 2, 3))).isFalse();
        // Nulls skipped — second non-null still equals first.
        assertThat(svc.isRapidOscillation(Arrays.asList(1, null, 1, 2))).isFalse();
    }

    @Test
    void isRapidOscillationTrueWhenTwoMostRecentDiffer() {
        assertThat(svc.isRapidOscillation(Arrays.asList(1, 2, 1, 2))).isTrue();
        assertThat(svc.isRapidOscillation(Arrays.asList(null, 3, null, 1))).isTrue();
    }

    // ---------- violatesCommunityRules ----------

    @Test
    void violatesCommunityRulesFalseOnEmptyViewport() {
        // total = 0 short-circuits before any random draw.
        LocalStats empty = new LocalStats(0, 0, 0, 0, 0, 0);
        Supplier<List<Integer>> noTypes = Collections::emptyList;

        assertThat(svc.violatesCommunityRules(
                empty, new boolean[0][0], new int[1][1], 0, 0, 1.0, noTypes))
                .isFalse();
    }

    @Test
    void violatesCommunityRulesFalseWhenAdherenceZeroDespiteRuleHits() {
        // Maximally bad stats — every rule hits — but gate = 0.0 so no Math.random
        // draw ever passes. Predicate must return false.
        LocalStats bad = new LocalStats(100, 1.0, 0.0, 0.0, 0.0, 0.0);  // 100% forest
        int[][] tiles = filled(10, 10, 1);
        boolean[][] highways = new boolean[10][10];
        Supplier<List<Integer>> oscillating = () -> Arrays.asList(1, 2);

        assertThat(svc.violatesCommunityRules(
                bad, highways, tiles, 5, 5, 0.0, oscillating))
                .isFalse();
    }

    @Test
    void violatesCommunityRulesTrueWhenAdherenceOneAndShareExtreme() {
        // adherence = 1.0 → gate = 0.6. Math.random() draw is in [0,1); a draw
        // < 0.6 fires the first rule (maxShare > 0.55). Run many trials and
        // assert at least one fires, pinning that the rule is reachable.
        LocalStats extreme = new LocalStats(100, 0.80, 0.05, 0.10, 0.05, 0.0);
        int[][] tiles = filled(10, 10, 0);
        boolean[][] highways = new boolean[10][10];
        Supplier<List<Integer>> none = Collections::emptyList;

        int hits = 0;
        for (int i = 0; i < 200; i++) {
            if (svc.violatesCommunityRules(extreme, highways, tiles, 5, 5, 1.0, none)) {
                hits++;
            }
        }
        // 200 trials at p ≥ 0.6 — vanishingly unlikely to see zero hits if wired.
        assertThat(hits).isGreaterThan(50);
    }

    @Test
    void violatesCommunityRulesSupplierNotInvokedWhenEarlierRuleShortCircuits() {
        // maxShare = 0.80 > 0.55 triggers the first rule. With adherence = 1.0
        // (gate = 0.6) it fires on roughly 60% of trials. When it fires, return
        // is immediate and the supplier must not be consulted.
        //
        // We can't strictly prove the supplier was *never* consulted across all
        // trials (later rules may fire on the other ~40%), but we can prove the
        // supplier is consulted strictly fewer times than the number of calls —
        // i.e. early-rule short-circuiting works.
        LocalStats extreme = new LocalStats(100, 0.80, 0.05, 0.10, 0.05, 0.0);
        int[][] tiles = filled(10, 10, 0);
        boolean[][] highways = new boolean[10][10];
        AtomicInteger supplierCalls = new AtomicInteger();
        Supplier<List<Integer>> counted = () -> {
            supplierCalls.incrementAndGet();
            return Arrays.asList(1, 2);  // oscillating, in case it gets reached
        };

        int trials = 200;
        for (int i = 0; i < trials; i++) {
            svc.violatesCommunityRules(extreme, highways, tiles, 5, 5, 1.0, counted);
        }

        assertThat(supplierCalls.get()).isLessThan(trials);
    }

    @Test
    void violatesCommunityRulesFalseWhenStatsBalancedAndConnectivityHealthy() {
        // Balanced viewport (no single share over 55%, nature ≥ 0.18,
        // industry ≥ 0.04) + healthy connectivity + non-oscillating history.
        // Every rule's first conjunct is false, so Math.random() is never
        // consulted and the predicate is deterministic.
        LocalStats balanced = new LocalStats(100, 0.30, 0.20, 0.30, 0.15, 0.05);
        int[][] tiles = filled(10, 10, 0);
        boolean[][] highways = new boolean[10][10];
        Supplier<List<Integer>> stable = () -> Arrays.asList(1, 1);

        for (int i = 0; i < 50; i++) {
            assertThat(svc.violatesCommunityRules(
                    balanced, highways, tiles, 5, 5, 1.0, stable))
                    .isFalse();
        }
    }

    // ---------- helpers ----------

    private static int[][] filled(int w, int h, int v) {
        int[][] grid = new int[h][w];
        for (int y = 0; y < h; y++) {
            Arrays.fill(grid[y], v);
        }
        return grid;
    }
}
