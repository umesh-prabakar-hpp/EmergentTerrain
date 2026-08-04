package com.emergentterrain.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 2.5 carve: pure game-rule predicates lifted out of {@link RoomService}.
 *
 * The service owns the five validation predicates that gate a proposed tile
 * change from passing bot voting:
 * <ul>
 *   <li>{@link #violatesCommunityRules} — composite gate (share extremes,
 *       nature floor, industry floor, access connectivity, rapid oscillation),
 *       each rule fired with probability {@code adherence * 0.6}.</li>
 *   <li>{@link #hasAccessConnectivity} — whether the largest connected
 *       component of access tiles in the viewport around the anchor covers at
 *       least 25% of the total access tiles.</li>
 *   <li>{@link #isAccessTile} — whether a tile coordinate is clear, road, or
 *       carrying a highway (passable for bots).</li>
 *   <li>{@link #isRapidOscillation} — whether the two most recent typed
 *       proposals disagree on their delta type.</li>
 * </ul>
 * {@code floodAccess} is the internal BFS that {@link #hasAccessConnectivity}
 * uses; it does not appear on the public surface.
 *
 * The service is stateless and has no Spring-injected dependencies — all I/O
 * (proposal lookups, JSON parsing) stays with the caller, which passes the
 * resolved input in via a {@link Supplier} so the lookup only fires when
 * earlier rules in {@code violatesCommunityRules} have not already returned.
 *
 * Viewport bounds for the access-connectivity check come from
 * {@link OccupancyService#computeViewBounds} (its
 * {@code visibleWidth() × visibleHeight()} window), so the access-rule
 * viewport stays in sync with proposal-anchor visibility math elsewhere.
 *
 * Pure refactor. No behavioural change — the {@code Math.random()} call order
 * inside {@link #violatesCommunityRules} matches the pre-carve implementation
 * exactly, so observable rejection rates are identical with the same RNG seed.
 */
@Service
public class TileValidatorService {

    private final OccupancyService occupancy;

    public TileValidatorService(OccupancyService occupancy) {
        this.occupancy = occupancy;
    }

    /**
     * Composite community-rules gate. Each rule fires with probability
     * {@code adherence * 0.6}; returns {@code true} on the first rule that
     * trips. The {@code recentDeltaTypesLazy} supplier is consulted only when
     * the rapid-oscillation rule is reached, so DB cost is paid only when
     * earlier rules don't short-circuit.
     */
    public boolean violatesCommunityRules(LocalStats after,
                                          boolean[][] highways,
                                          int[][] tiles,
                                          int anchorX,
                                          int anchorY,
                                          double adherence,
                                          Supplier<List<Integer>> recentDeltaTypesLazy) {
        if (after.total() == 0) {
            return false;
        }
        double maxShare = Math.max(Math.max(after.forest(), after.water()), Math.max(after.clear(), after.industry()));
        double gate = adherence * 0.6;
        if (maxShare > 0.55 && Math.random() < gate) {
            return true;
        }
        double nature = after.forest() + after.water();
        if (nature < 0.18 && Math.random() < gate) {
            return true;
        }
        if (after.industry() < 0.04 && Math.random() < gate) {
            return true;
        }
        if (!hasAccessConnectivity(tiles, highways, anchorX, anchorY) && Math.random() < gate) {
            return true;
        }
        if (isRapidOscillation(recentDeltaTypesLazy.get()) && Math.random() < gate) {
            return true;
        }
        return false;
    }

    /**
     * True when the largest connected component of access tiles in the
     * viewport centred on the anchor covers at least 25% of the total access
     * tiles. Empty-viewport (no access tiles at all) returns {@code true} —
     * there's nothing to fragment.
     */
    public boolean hasAccessConnectivity(int[][] tiles, boolean[][] highways, int anchorX, int anchorY) {
        int[] view = occupancy.computeViewBounds(anchorX, anchorY, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        boolean[][] visited = new boolean[viewH][viewW];
        int totalAccess = 0;
        int largest = 0;
        for (int y = 0; y < viewH; y++) {
            for (int x = 0; x < viewW; x++) {
                int gx = viewX + x;
                int gy = viewY + y;
                if (!isAccessTile(tiles, highways, gx, gy)) {
                    continue;
                }
                totalAccess++;
                if (visited[y][x]) {
                    continue;
                }
                int size = floodAccess(tiles, highways, viewX, viewY, viewW, viewH, x, y, visited);
                largest = Math.max(largest, size);
            }
        }
        if (totalAccess == 0) {
            return true;
        }
        double ratio = (double) largest / totalAccess;
        return ratio >= 0.25;
    }

    /** A tile is "access" if it is clear (0), road (4), or carries a highway. */
    public boolean isAccessTile(int[][] tiles, boolean[][] highways, int x, int y) {
        int v = tiles[y][x];
        return v == 0 || v == 4 || (highways.length > 0 && highways[y][x]);
    }

    /**
     * True if the two most recent non-null delta types in
     * {@code recentDeltaTypes} disagree. Nulls (proposals without a typed
     * delta) are skipped. Fewer than two typed proposals returns {@code false}
     * — there's no oscillation pattern yet.
     */
    public boolean isRapidOscillation(List<Integer> recentDeltaTypes) {
        if (recentDeltaTypes == null || recentDeltaTypes.isEmpty()) {
            return false;
        }
        Integer a = null;
        Integer b = null;
        for (Integer t : recentDeltaTypes) {
            if (t == null) continue;
            if (a == null) {
                a = t;
            } else {
                b = t;
                break;
            }
        }
        if (a == null || b == null) {
            return false;
        }
        return !a.equals(b);
    }

    private int floodAccess(int[][] tiles, boolean[][] highways,
                            int viewX, int viewY, int viewW, int viewH,
                            int sx, int sy, boolean[][] visited) {
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { sx, sy });
        visited[sy][sx] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            count++;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= viewW || ny >= viewH) {
                    continue;
                }
                if (visited[ny][nx]) {
                    continue;
                }
                int gx = viewX + nx;
                int gy = viewY + ny;
                if (!isAccessTile(tiles, highways, gx, gy)) {
                    continue;
                }
                visited[ny][nx] = true;
                queue.add(new int[] { nx, ny });
            }
        }
        return count;
    }

}
