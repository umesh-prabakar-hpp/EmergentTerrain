package com.emergentterrain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Phase 2.5 carve: emergent-highway promotion / decay + per-move road-pressure
 * accounting, lifted out of {@link RoomService} ahead of the broader carve.
 *
 * Three concerns live here:
 * <ul>
 *   <li><b>Pressure seeding</b> — when a room first loads, every existing
 *       highway tile gets a starting pressure so it survives the next decay
 *       pass. {@link #seedRoadPressure(boolean[][])}.</li>
 *   <li><b>Per-move pressure deposit</b> — when a bot moves, traffic adds
 *       pressure to the from / to endpoints. The amount depends on whether the
 *       move is a long-distance relocation or routine exploration.
 *       {@link #computeMoveDeposit}.</li>
 *   <li><b>Evolution pass</b> — periodic decay + promotion + removal that
 *       sweeps the whole grid, mutating the highway and road-pressure arrays
 *       in place. {@link #evolve}.</li>
 * </ul>
 *
 * The service intentionally takes <em>arrays</em> (not the {@code RoomService}
 * cache type) so it stays decoupled from the caller's caching strategy. The
 * caller is responsible for invalidating its cache, recomputing tile counts,
 * and persisting the mutated arrays.
 *
 * Pure refactor. No behavioural change. Defaults match the previous inline
 * values bound from {@code application.yml} so existing rooms behave
 * identically after the carve.
 *
 * Gated by {@code app.highways.emergent.enabled} (default {@code true} —
 * matches the pre-carve default). When disabled, {@link #computeMoveDeposit}
 * returns {@link MoveDeposit#ZERO} and {@link #evolve} returns {@code false}
 * without touching the input arrays.
 */
@Service
public class HighwayEmergenceService {

    /** Pressure deposit produced by a single bot move at its from / to endpoints. */
    public record MoveDeposit(double fromDeposit, double toDeposit) {
        public static final MoveDeposit ZERO = new MoveDeposit(0d, 0d);
        public boolean isZero() { return fromDeposit == 0d && toDeposit == 0d; }
    }

    private final boolean enabled;
    private final int     longTripMinDistance;
    private final double  travelPressure;
    private final double  explorationPressure;
    private final double  decayPerMaintenance;
    private final double  promoteThreshold;
    private final double  preserveThreshold;
    private final double  removeThreshold;
    private final double  maxCoverageRatio;
    private final double  localDensityCap;
    private final int     localDensityRadius;
    private final double  seedPressure;

    public HighwayEmergenceService(
            @Value("${app.highways.emergent.enabled:true}") boolean enabled,
            @Value("${app.highways.emergent.longTripMinDistance:12}") int longTripMinDistance,
            @Value("${app.highways.emergent.travelPressure:0.35}") double travelPressure,
            @Value("${app.highways.emergent.explorationPressure:0.08}") double explorationPressure,
            @Value("${app.highways.emergent.decayPerMaintenance:0.002}") double decayPerMaintenance,
            @Value("${app.highways.emergent.promoteThreshold:1.0}") double promoteThreshold,
            @Value("${app.highways.emergent.preserveThreshold:0.35}") double preserveThreshold,
            @Value("${app.highways.emergent.removeThreshold:0.12}") double removeThreshold,
            @Value("${app.highways.emergent.maxCoverageRatio:0.08}") double maxCoverageRatio,
            @Value("${app.highways.emergent.localDensityCap:0.4}") double localDensityCap,
            @Value("${app.highways.emergent.localDensityRadius:3}") int localDensityRadius,
            @Value("${app.highways.emergent.seedPressure:0.65}") double seedPressure) {
        this.enabled             = enabled;
        this.longTripMinDistance = Math.max(2, longTripMinDistance);
        this.travelPressure      = Math.max(0d, travelPressure);
        this.explorationPressure = Math.max(0d, explorationPressure);
        this.decayPerMaintenance = Math.max(0d, decayPerMaintenance);
        this.promoteThreshold    = Math.max(0.1d, promoteThreshold);
        this.preserveThreshold   = Math.max(0d, preserveThreshold);
        this.removeThreshold     = Math.max(0d, Math.min(this.preserveThreshold, removeThreshold));
        this.maxCoverageRatio    = Math.max(0.01d, Math.min(0.5d, maxCoverageRatio));
        this.localDensityCap     = Math.max(0.05d, Math.min(1d, localDensityCap));
        this.localDensityRadius  = Math.max(1, localDensityRadius);
        this.seedPressure        = Math.max(0d, seedPressure);
    }

    public boolean enabled() { return enabled; }

    /** Canonical pressure floor for an existing highway tile. Exposed so deserializers can apply it. */
    public double seedPressure() { return seedPressure; }

    /** Initial pressure map sized to {@code highways}; every highway tile gets {@code seedPressure}. */
    public double[][] seedRoadPressure(boolean[][] highways) {
        int height = highways == null ? 0 : highways.length;
        int width  = height == 0 || highways[0] == null ? 0 : highways[0].length;
        return seedRoadPressure(highways, height, width);
    }

    /** Variant for callers that already know the canonical (height, width) and want to allocate to that shape. */
    public double[][] seedRoadPressure(boolean[][] highways, int height, int width) {
        double[][] pressure = new double[height][width];
        if (highways == null || highways.length == 0) return pressure;
        for (int y = 0; y < Math.min(height, highways.length); y++) {
            for (int x = 0; x < Math.min(width, highways[y].length); x++) {
                if (highways[y][x]) pressure[y][x] = seedPressure;
            }
        }
        return pressure;
    }

    /**
     * Pressure deposit from a single bot move. Long-distance relocations
     * trigger {@code travelPressure}; mid-distance moves by mobile bots
     * trigger {@code explorationPressure}. Endpoint multipliers favour
     * non-highway tiles so traffic accumulates where new highways might
     * emerge, not where they already exist.
     */
    public MoveDeposit computeMoveDeposit(Map<String, Double> profile,
                                           int travelDistance,
                                           boolean relocating,
                                           int botPlanLength,
                                           boolean fromIsHighway,
                                           boolean toIsHighway) {
        if (!enabled) return MoveDeposit.ZERO;
        double mobility = profile == null ? 0.5d : profile.getOrDefault("mobility", 0.5d);
        double pressure = 0d;
        if (relocating && travelDistance >= longTripMinDistance) {
            pressure = travelPressure * (1d + Math.min(1.5d, travelDistance / (double) longTripMinDistance));
        } else if (mobility > 0.55d && travelDistance >= Math.max(2, botPlanLength)) {
            pressure = explorationPressure * (0.5d + mobility);
        }
        if (pressure <= 0d) return MoveDeposit.ZERO;
        return new MoveDeposit(
                pressure * (fromIsHighway ? 0.65d : 1d),
                pressure * (toIsHighway   ? 0.8d : 1.2d));
    }

    /**
     * One pass of the highway evolution loop: decay every pressure cell, remove
     * highway tiles that fall below {@code removeThreshold}, then promote
     * non-highway non-water tiles that exceed {@code promoteThreshold} (subject
     * to a local-density cap and a global coverage cap). Mutates {@code highways}
     * and {@code roadPressure} in place. Returns {@code true} when at least one
     * cell flipped.
     */
    public boolean evolve(int[][] tiles, boolean[][] highways, double[][] roadPressure) {
        if (!enabled) return false;
        if (tiles == null || tiles.length == 0
                || highways == null || highways.length == 0
                || roadPressure == null || roadPressure.length == 0) {
            return false;
        }
        int height = tiles.length;
        int width  = tiles[0].length;
        int maxHighways  = Math.max(1, (int) Math.round(width * height * maxCoverageRatio));
        int highwayCount = countHighways(highways);
        boolean changed  = false;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                roadPressure[y][x] = Math.max(0d, roadPressure[y][x] - decayPerMaintenance);
                if (highways[y][x] && roadPressure[y][x] < removeThreshold) {
                    highways[y][x] = false;
                    highwayCount--;
                    changed = true;
                }
            }
        }
        for (int y = 0; y < height && highwayCount < maxHighways; y++) {
            for (int x = 0; x < width && highwayCount < maxHighways; x++) {
                if (highways[y][x]) {
                    roadPressure[y][x] = Math.max(roadPressure[y][x], preserveThreshold);
                    continue;
                }
                if (tiles[y][x] == 2) continue;
                if (roadPressure[y][x] < promoteThreshold) continue;
                if (localHighwayDensity(highways, x, y, localDensityRadius) >= localDensityCap) continue;
                highways[y][x] = true;
                roadPressure[y][x] = Math.max(roadPressure[y][x], preserveThreshold);
                highwayCount++;
                changed = true;
            }
        }
        return changed;
    }

    private static double localHighwayDensity(boolean[][] highways, int cx, int cy, int radius) {
        int total = 0;
        int roads = 0;
        for (int y = Math.max(0, cy - radius); y <= Math.min(highways.length - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(highways[0].length - 1, cx + radius); x++) {
                total++;
                if (highways[y][x]) roads++;
            }
        }
        return total == 0 ? 0d : roads / (double) total;
    }

    private static int countHighways(boolean[][] highways) {
        int n = 0;
        for (boolean[] row : highways) {
            for (boolean cell : row) {
                if (cell) n++;
            }
        }
        return n;
    }
}
