package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2.5 carve: per-room coarse-grid landscape index used by the bot
 * relocation logic to pick a target tile that matches a bot's profile. Lifted
 * out of {@link RoomService} so the spatial-index concern has its own service
 * with its own tests, and so {@link BotMovementService#findBestProfileTarget}
 * can consume {@link CachedMap} directly.
 *
 * The service owns one {@link ConcurrentHashMap} keyed by room id. Each entry
 * carries a content hash of the (tiles, highways) pair it was built from;
 * {@link #getCachedMap} compares that hash before reusing the entry, so a
 * rebuild fires when the world changes underneath.
 *
 * Pure refactor. No behavioural change. The viewport dimensions
 * ({@link #VISIBLE_WIDTH} / {@link #VISIBLE_HEIGHT}) and stride formula are
 * preserved verbatim from the inline constants that lived in
 * {@link RoomService}; {@link RoomService#computeViewBounds} and
 * {@link RoomService#inView} continue to use the same numeric values
 * (duplicated as RoomService-private constants — a future {@code OccupancyService}
 * carve will fold those reads into this service's accessors).
 *
 * Internal {@code hashTiles} helper duplicates the SHA-256 digest used by
 * {@link RoomService} and {@link RoomStateOrchestrator}; that's intentional
 * to keep the three services decoupled.
 */
@Service
public class MapIndexService {

    /** Viewport width used for coarse-cell averaging windows. Mirrors RoomService's value. */
    static final int VISIBLE_WIDTH  = 48;
    /** Viewport height used for coarse-cell averaging windows. Mirrors RoomService's value. */
    static final int VISIBLE_HEIGHT = 36;

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, CachedMap> mapCache = new ConcurrentHashMap<>();

    public MapIndexService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get-or-build the coarse map for {@code room}. A cached entry is reused only
     * when its content hash matches the current (tiles, highways) snapshot; any
     * world change forces a rebuild on the next access.
     */
    public CachedMap getCachedMap(Room room, int[][] tiles, boolean[][] highways) {
        String key = hashTiles(tiles) + "|" + Arrays.deepHashCode(highways);
        CachedMap cached = mapCache.get(room.getId());
        if (cached != null && key.equals(cached.key)) {
            return cached;
        }
        CachedMap built = buildCachedMap(tiles, highways, key);
        mapCache.put(room.getId(), built);
        return built;
    }

    /** O(1) sum of any axis-aligned rectangle in a summed-area table. */
    public int rectSum(int[][] integral, int x0, int y0, int x1, int y1) {
        return integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
    }

    // ---- internals ----

    private CachedMap buildCachedMap(int[][] tiles, boolean[][] highways, String key) {
        int h = tiles.length;
        int w = tiles[0].length;
        IntegralMaps integrals = buildIntegrals(tiles, highways);
        int viewW = Math.min(w, VISIBLE_WIDTH);
        int viewH = Math.min(h, VISIBLE_HEIGHT);
        int step = Math.max(4, Math.min(12, Math.min(w, h) / 12));
        List<CoarseCell> cells = new ArrayList<>();
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int viewX = Math.max(0, Math.min(w - viewW, x - viewW / 2));
                int viewY = Math.max(0, Math.min(h - viewH, y - viewH / 2));
                int x0 = viewX;
                int y0 = viewY;
                int x1 = viewX + viewW;
                int y1 = viewY + viewH;
                int total = viewW * viewH;
                if (total == 0) continue;
                double forest   = rectSum(integrals.forest,   x0, y0, x1, y1) / (double) total;
                double water    = rectSum(integrals.water,    x0, y0, x1, y1) / (double) total;
                double clear    = rectSum(integrals.clear,    x0, y0, x1, y1) / (double) total;
                double industry = rectSum(integrals.industry, x0, y0, x1, y1) / (double) total;
                double road     = rectSum(integrals.road,     x0, y0, x1, y1) / (double) total;
                cells.add(new CoarseCell(x, y, forest, water, clear, industry, road));
            }
        }
        return new CachedMap(key, integrals, cells, viewW, viewH, step);
    }

    private IntegralMaps buildIntegrals(int[][] tiles, boolean[][] highways) {
        int h = tiles.length;
        int w = tiles[0].length;
        int[][] forest   = new int[h + 1][w + 1];
        int[][] water    = new int[h + 1][w + 1];
        int[][] clear    = new int[h + 1][w + 1];
        int[][] industry = new int[h + 1][w + 1];
        int[][] road     = new int[h + 1][w + 1];
        for (int y = 1; y <= h; y++) {
            for (int x = 1; x <= w; x++) {
                int v = tiles[y - 1][x - 1];
                int f  = (v == 1) ? 1 : 0;
                int wa = (v == 2) ? 1 : 0;
                int c  = (v == 0) ? 1 : 0;
                int i  = (v == 3) ? 1 : 0;
                int r  = ((highways.length > 0 && highways[y - 1][x - 1]) || v == 4) ? 1 : 0;
                forest[y][x]   = forest[y - 1][x]   + forest[y][x - 1]   - forest[y - 1][x - 1]   + f;
                water[y][x]    = water[y - 1][x]    + water[y][x - 1]    - water[y - 1][x - 1]    + wa;
                clear[y][x]    = clear[y - 1][x]    + clear[y][x - 1]    - clear[y - 1][x - 1]    + c;
                industry[y][x] = industry[y - 1][x] + industry[y][x - 1] - industry[y - 1][x - 1] + i;
                road[y][x]     = road[y - 1][x]     + road[y][x - 1]     - road[y - 1][x - 1]     + r;
            }
        }
        return new IntegralMaps(forest, water, clear, industry, road);
    }

    private String hashTiles(int[][] tiles) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(tiles);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tiles", e);
        }
    }
}
