package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the behaviour of {@link HighwayEmergenceService} so the Phase 2.5 carve
 * out of {@link RoomService} cannot regress. Defaults mirror the inline
 * tunables that lived in RoomService before this extraction.
 */
class HighwayEmergenceServiceTest {

    /** Construct with the same defaults as the @Value-injected production bean. */
    private HighwayEmergenceService defaults() {
        return new HighwayEmergenceService(
                /* enabled */              true,
                /* longTripMinDistance */  12,
                /* travelPressure */       0.35,
                /* explorationPressure */  0.08,
                /* decayPerMaintenance */  0.002,
                /* promoteThreshold */     1.0,
                /* preserveThreshold */    0.35,
                /* removeThreshold */      0.12,
                /* maxCoverageRatio */     0.08,
                /* localDensityCap */      0.4,
                /* localDensityRadius */   3,
                /* seedPressure */         0.65);
    }

    @Test
    void seedRoadPressureFloorsExistingHighwayTiles() {
        HighwayEmergenceService svc = defaults();
        boolean[][] highways = new boolean[][]{
                { false, true,  false },
                { false, false, false },
                { true,  false, false }};

        double[][] pressure = svc.seedRoadPressure(highways);

        assertThat(pressure[0][1]).isEqualTo(0.65);
        assertThat(pressure[2][0]).isEqualTo(0.65);
        assertThat(pressure[0][0]).isEqualTo(0.0);
        assertThat(pressure[1][1]).isEqualTo(0.0);
    }

    @Test
    void seedRoadPressureHandlesEmptyAndNullInputs() {
        HighwayEmergenceService svc = defaults();
        assertThat(svc.seedRoadPressure(null)).isEmpty();
        assertThat(svc.seedRoadPressure(new boolean[0][0])).isEmpty();
    }

    @Test
    void disabledServiceProducesZeroDeposits() {
        HighwayEmergenceService disabled = new HighwayEmergenceService(
                false, 12, 0.35, 0.08, 0.002, 1.0, 0.35, 0.12, 0.08, 0.4, 3, 0.65);

        HighwayEmergenceService.MoveDeposit deposit = disabled.computeMoveDeposit(
                Map.of("mobility", 0.9), 50, true, 3, false, false);

        assertThat(deposit).isEqualTo(HighwayEmergenceService.MoveDeposit.ZERO);
        assertThat(deposit.isZero()).isTrue();
    }

    @Test
    void longRelocationGeneratesTravelPressure() {
        HighwayEmergenceService svc = defaults();
        // travelDistance == longTripMinDistance gives factor 1 + min(1.5, 12/12) = 2.0,
        // so raw pressure = 0.35 * 2.0 = 0.70.
        HighwayEmergenceService.MoveDeposit deposit = svc.computeMoveDeposit(
                Map.of(), 12, true, 3, false, false);

        // Both endpoints non-highway: from = 0.70 * 1.0, to = 0.70 * 1.2.
        assertThat(deposit.fromDeposit()).isEqualTo(0.70, within(1e-9));
        assertThat(deposit.toDeposit()).isEqualTo(0.84, within(1e-9));
    }

    @Test
    void travelPressureCapsLongDistanceAtFactor25x() {
        HighwayEmergenceService svc = defaults();
        // travelDistance = 1000 → ratio = 83 but cap is 1.5 → factor = 2.5.
        HighwayEmergenceService.MoveDeposit deposit = svc.computeMoveDeposit(
                Map.of(), 1000, true, 3, false, false);

        // Raw pressure = 0.35 * 2.5 = 0.875.
        assertThat(deposit.fromDeposit()).isEqualTo(0.875, within(1e-9));
    }

    @Test
    void mobileExplorationGeneratesExplorationPressure() {
        HighwayEmergenceService svc = defaults();
        // mobility=0.8 (>0.55), travelDistance=5 (>=max(2,3)), not relocating.
        // Raw pressure = 0.08 * (0.5 + 0.8) = 0.104.
        HighwayEmergenceService.MoveDeposit deposit = svc.computeMoveDeposit(
                Map.of("mobility", 0.8), 5, false, 3, false, false);

        assertThat(deposit.fromDeposit()).isEqualTo(0.104, within(1e-9));
        assertThat(deposit.toDeposit()).isEqualTo(0.104 * 1.2, within(1e-9));
    }

    @Test
    void lowMobilityShortMoveProducesZeroPressure() {
        HighwayEmergenceService svc = defaults();
        HighwayEmergenceService.MoveDeposit deposit = svc.computeMoveDeposit(
                Map.of("mobility", 0.4), 5, false, 3, false, false);

        assertThat(deposit.isZero()).isTrue();
    }

    @Test
    void existingHighwayEndpointsGetReducedDeposit() {
        HighwayEmergenceService svc = defaults();
        // Same long relocation; from is on highway, to is on highway.
        HighwayEmergenceService.MoveDeposit deposit = svc.computeMoveDeposit(
                Map.of(), 12, true, 3, true, true);

        // Raw pressure = 0.70; from = 0.70 * 0.65 = 0.455, to = 0.70 * 0.8 = 0.56.
        assertThat(deposit.fromDeposit()).isEqualTo(0.455, within(1e-9));
        assertThat(deposit.toDeposit()).isEqualTo(0.56, within(1e-9));
    }

    @Test
    void evolveDecaysPressureAndRemovesUnderusedHighways() {
        HighwayEmergenceService svc = defaults();
        int[][] tiles = new int[5][5];
        boolean[][] highways = new boolean[5][5];
        double[][] pressure = new double[5][5];
        // One highway tile with low pressure (below remove threshold once decayed).
        highways[2][2] = true;
        pressure[2][2] = 0.10; // > 0.002 decay leaves 0.098 < 0.12 removeThreshold.

        boolean changed = svc.evolve(tiles, highways, pressure);

        assertThat(changed).isTrue();
        assertThat(highways[2][2]).isFalse();
    }

    @Test
    void evolvePromotesTilesAbovePromoteThreshold() {
        HighwayEmergenceService svc = defaults();
        int[][] tiles = new int[10][10]; // all clear, no water
        boolean[][] highways = new boolean[10][10];
        double[][] pressure = new double[10][10];
        // Spread several candidates so none collide with the local-density cap (0.4
        // within radius 3). With one promoted tile at (0,0), that's 1 highway in a
        // 4x4 viewport = 1/16 = 0.0625, well under cap. Subsequent promotes stay
        // far apart.
        pressure[0][0] = 1.5;
        pressure[5][5] = 1.5;
        pressure[9][9] = 1.5;

        boolean changed = svc.evolve(tiles, highways, pressure);

        assertThat(changed).isTrue();
        assertThat(highways[0][0]).isTrue();
        assertThat(highways[5][5]).isTrue();
        assertThat(highways[9][9]).isTrue();
        // Each promoted tile floors at preserveThreshold (0.35) — but ours started
        // at 1.5 so they retain 1.5 - decayPerMaintenance.
        assertThat(pressure[0][0]).isEqualTo(1.498, within(1e-9));
    }

    @Test
    void evolveSkipsWaterTilesEvenWithHighPressure() {
        HighwayEmergenceService svc = defaults();
        int[][] tiles = new int[3][3];
        tiles[1][1] = 2; // water
        boolean[][] highways = new boolean[3][3];
        double[][] pressure = new double[3][3];
        pressure[1][1] = 5.0;

        boolean changed = svc.evolve(tiles, highways, pressure);

        assertThat(changed).isFalse();
        assertThat(highways[1][1]).isFalse();
    }

    @Test
    void evolveReturnsFalseOnEmptyArrays() {
        HighwayEmergenceService svc = defaults();
        assertThat(svc.evolve(new int[0][0], new boolean[0][0], new double[0][0])).isFalse();
        assertThat(svc.evolve(null, new boolean[0][0], new double[0][0])).isFalse();
    }

    @Test
    void seedPressureGetterReturnsConfiguredValue() {
        assertThat(defaults().seedPressure()).isEqualTo(0.65);
    }
}
