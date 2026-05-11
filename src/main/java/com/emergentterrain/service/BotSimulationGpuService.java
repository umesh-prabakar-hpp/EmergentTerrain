package com.emergentterrain.service;

import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.TileEdit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2.5 carve: GPU bot-simulation primitives lifted out of
 * {@link RoomService}. Owns the per-room {@link GpuWorldCache} map and the
 * pure helpers that build the request payload, encode/decode bot motion
 * state into the GPU's binary buffer, diff the world for incremental
 * updates, and apply the engine's returned tile edits to a tiles + highways
 * array.
 *
 * Scope deliberately narrower than the design doc's enumerated list:
 * {@code trySimulateBotsWithGpu} (the orchestrator) and
 * {@code applyGpuEditsToCachedState} (the cached-state writer) stay in
 * {@link RoomService}. The orchestrator threads many RoomService internals
 * — bot/profile codecs, cached-state retrieval, road-pressure deposit,
 * shared-tokens / shared-quests updates — that don't move cleanly without
 * also dragging the cache map across services. Same scope-reduction
 * pattern as the {@code RoomStateOrchestrator} and {@code OccupancyService}
 * carves: the pure decision / codec / cache management moves; the
 * cache-coupled write stays put. RoomService keeps both methods as thin
 * orchestrators that delegate the pure operations to this service.
 *
 * Pure refactor. The world-cache rebuild policy (full-send when grid sizes
 * mismatch, diff-send up to {@code maxEdits}, fall back to full when the
 * diff exceeds the budget) is unchanged; the binary layout of the bot
 * motion state buffer is byte-for-byte identical to the pre-carve
 * implementation so existing GPU engine builds keep working.
 */
@Service
public class BotSimulationGpuService {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, GpuWorldCache> gpuWorldCache = new ConcurrentHashMap<>();

    public BotSimulationGpuService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the cached world mirror for {@code roomId}, creating one with
     * a fresh world id on first access. The id is stable across diff sends
     * and only rotates when the cache is explicitly cleared or replaced
     * (which today happens only on a full-world resync).
     */
    public GpuWorldCache getOrCreateCache(UUID roomId) {
        return gpuWorldCache.computeIfAbsent(roomId,
            ignored -> new GpuWorldCache(UUID.randomUUID().toString()));
    }

    /** Drops the cached world mirror for {@code roomId} so the next send re-publishes the full grid. */
    public void clearCache(UUID roomId) {
        gpuWorldCache.remove(roomId);
    }

    /**
     * Serialises the room-level GPU bot parameters as a JSON string. Missing
     * fields fall through to {@code "{}"} on any serialisation failure so a
     * partial config never blocks the request.
     */
    public String buildGpuBotParams(UUID roomId, int width, int height,
                                    int neighborRadius, int minNeighbors,
                                    int densityAreaSize, int maxOccupants) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("roomId", roomId.toString());
            params.put("width", width);
            params.put("height", height);
            params.put("neighborRadius", neighborRadius);
            params.put("minNeighbors", minNeighbors);
            params.put("densityAreaSize", densityAreaSize);
            params.put("maxOccupants", maxOccupants);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Stamps the per-bot profile weights (forest/water/industry/clear/mobility)
     * onto {@code botState}, optionally biased by per-tile {@code nudges} that
     * push individual axes up (clamped to 1.0).
     */
    public void applyGpuBotProfile(BotState.Builder botState,
                                   Map<String, Double> profile,
                                   Map<String, Double> nudges) {
        botState
            .setForest(nudgedWeight(profile, nudges, "forest"))
            .setWater(nudgedWeight(profile, nudges, "water"))
            .setIndustry(nudgedWeight(profile, nudges, "industry"))
            .setClear(nudgedWeight(profile, nudges, "clear"))
            .setMobility(profile.getOrDefault("mobility", 0.5).floatValue());
    }

    private float nudgedWeight(Map<String, Double> profile, Map<String, Double> nudges, String key) {
        double base = profile.getOrDefault(key, 0.5);
        if (nudges != null && nudges.containsKey(key)) {
            base = Math.min(1.0, base + nudges.get(key));
        }
        return (float) base;
    }

    /**
     * Produces the {@code tile_edits} list needed to bring an old mirror of
     * the world up to the new one. Returns {@code null} when the diff exceeds
     * {@code maxEdits} so the caller can fall back to a full-world resync;
     * an empty list means the grids are identical. The encoding folds a
     * "highway-on" bit into a synthetic type-4 (road) edit, matching the
     * pre-carve protocol.
     */
    public List<TileEdit> collectGpuTileDiffs(int[][] oldTiles,
                                              boolean[][] oldHighways,
                                              int[][] newTiles,
                                              boolean[][] newHighways,
                                              int maxEdits) {
        int height = newTiles.length;
        int width = height == 0 ? 0 : newTiles[0].length;
        if (width == 0 || height == 0) {
            return Collections.emptyList();
        }
        List<TileEdit> edits = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int oldTile = oldTiles[y][x];
                int newTile = newTiles[y][x];
                boolean oldHighway = oldHighways.length > 0 && oldHighways[y][x];
                boolean newHighway = newHighways.length > 0 && newHighways[y][x];
                if (oldTile == newTile && oldHighway == newHighway) {
                    continue;
                }
                int type = newHighway ? 4 : newTile;
                edits.add(TileEdit.newBuilder().setX(x).setY(y).setType(type).build());
                if (maxEdits > 0 && edits.size() > maxEdits) {
                    return null;
                }
            }
        }
        return edits;
    }

    /**
     * Applies a list of {@code tile_edits} in place to the cached mirror.
     * Out-of-bounds entries are ignored. Type-4 (highway) entries set the
     * highway bit and clear the underlying tile to 0, matching the
     * pre-carve protocol.
     */
    public void applyTileEditsToCache(int[][] tiles, boolean[][] highways, List<TileEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        int height = tiles.length;
        int width = height == 0 ? 0 : tiles[0].length;
        for (TileEdit edit : edits) {
            int x = edit.getX();
            int y = edit.getY();
            if (x < 0 || y < 0 || y >= height || x >= width) {
                continue;
            }
            int type = edit.getType();
            if (type == 4) {
                tiles[y][x] = 0;
                if (highways.length > 0) {
                    highways[y][x] = true;
                }
            } else {
                tiles[y][x] = type;
            }
        }
    }

    /**
     * Packs a {@link BotMotionState} into the 40-byte little-endian buffer
     * the GPU engine expects: {@code lastX | lastY | headingX | headingY |
     * reverseCooldown | planSize | step0X | step0Y | step1X | step1Y}.
     * Null coordinates marshal as {@link Integer#MIN_VALUE}. The state is
     * normalised before encoding so the planned-steps cap of 2 is honoured.
     */
    public byte[] encodeBotMotionStateBytes(BotMotionState state) {
        state.normalize();
        ByteBuffer buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(state.lastX == null ? Integer.MIN_VALUE : state.lastX);
        buffer.putInt(state.lastY == null ? Integer.MIN_VALUE : state.lastY);
        buffer.putInt(state.headingX);
        buffer.putInt(state.headingY);
        buffer.putInt(state.reverseCooldownTicks);
        buffer.putInt(state.plannedSteps.size());
        for (int i = 0; i < 2; i++) {
            if (i < state.plannedSteps.size()) {
                int[] step = state.plannedSteps.get(i);
                buffer.putInt(step[0]);
                buffer.putInt(step[1]);
            } else {
                buffer.putInt(Integer.MIN_VALUE);
                buffer.putInt(Integer.MIN_VALUE);
            }
        }
        return buffer.array();
    }

    /**
     * Unpacks the GPU engine's response buffer back into the supplied
     * {@code seed} state, then snaps {@code currentX} / {@code currentY} to
     * {@code (botX, botY)} (whatever positions the engine declared for the
     * bot). The seed is mutated in place and returned. If {@code bytes} is
     * shorter than the 40-byte payload, the seed is returned unchanged
     * apart from the normalise call inside the seed's lifecycle (no-op when
     * the caller passes a freshly read state). Caller supplies the seed —
     * typically the result of {@code readBotMotionState(bot)} — so the
     * codec stays pure.
     */
    public BotMotionState decodeBotMotionStateBytes(byte[] bytes,
                                                    BotMotionState seed,
                                                    Integer botX,
                                                    Integer botY) {
        if (bytes == null || bytes.length < 40) {
            return seed;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int lastX = buffer.getInt();
        int lastY = buffer.getInt();
        seed.lastX = lastX == Integer.MIN_VALUE ? null : lastX;
        seed.lastY = lastY == Integer.MIN_VALUE ? null : lastY;
        seed.headingX = buffer.getInt();
        seed.headingY = buffer.getInt();
        seed.reverseCooldownTicks = Math.max(0, buffer.getInt());
        int planSize = Math.max(0, Math.min(2, buffer.getInt()));
        seed.plannedSteps.clear();
        for (int i = 0; i < 2; i++) {
            int x = buffer.getInt();
            int y = buffer.getInt();
            if (i < planSize && x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
                seed.plannedSteps.add(new int[] { x, y });
            }
        }
        seed.currentX = botX;
        seed.currentY = botY;
        seed.normalize();
        return seed;
    }
}
