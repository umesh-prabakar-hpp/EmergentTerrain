package com.emergentterrain.service;

import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.TileEdit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the behaviour of {@link BotSimulationGpuService} so the Phase 2.5
 * carve out of {@link RoomService} cannot regress. Expected values mirror
 * the inline implementation that lived in RoomService before this
 * extraction. The binary format of the GPU bot-state buffer is asserted
 * round-trip-and-byte-level because the GPU engine on the other side
 * decodes this layout directly.
 */
class BotSimulationGpuServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BotSimulationGpuService svc = new BotSimulationGpuService(objectMapper);

    // ---------- cache management ----------

    @Test
    void getOrCreateCacheIsIdempotentPerRoomAndKeepsWorldIdStable() {
        UUID room = UUID.randomUUID();
        GpuWorldCache a = svc.getOrCreateCache(room);
        GpuWorldCache b = svc.getOrCreateCache(room);

        assertThat(b).isSameAs(a);
        assertThat(a.worldId).isNotBlank();
    }

    @Test
    void clearCacheDropsTheEntryAndNextGetReturnsFreshWorldId() {
        UUID room = UUID.randomUUID();
        GpuWorldCache first = svc.getOrCreateCache(room);
        svc.clearCache(room);
        GpuWorldCache second = svc.getOrCreateCache(room);

        assertThat(second).isNotSameAs(first);
        assertThat(second.worldId).isNotEqualTo(first.worldId);
    }

    // ---------- buildGpuBotParams ----------

    @Test
    void buildGpuBotParamsEmitsAllRequiredKeys() throws Exception {
        UUID room = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = svc.buildGpuBotParams(room, 64, 48, 5, 3, 20, 8);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        assertThat(parsed.get("roomId")).isEqualTo(room.toString());
        assertThat(parsed.get("width")).isEqualTo(64);
        assertThat(parsed.get("height")).isEqualTo(48);
        assertThat(parsed.get("neighborRadius")).isEqualTo(5);
        assertThat(parsed.get("minNeighbors")).isEqualTo(3);
        assertThat(parsed.get("densityAreaSize")).isEqualTo(20);
        assertThat(parsed.get("maxOccupants")).isEqualTo(8);
    }

    // ---------- applyGpuBotProfile ----------

    @Test
    void applyGpuBotProfileSetsBaseWeightsAndMobilityFromProfile() {
        BotState.Builder b = BotState.newBuilder().setId("B:x").setX(0).setY(0);
        Map<String, Double> profile = profile(0.3, 0.2, 0.4, 0.5, 0.7);

        svc.applyGpuBotProfile(b, profile, null);

        assertThat(b.getForest()).isEqualTo(0.3f, within(1e-6f));
        assertThat(b.getWater()).isEqualTo(0.2f, within(1e-6f));
        assertThat(b.getIndustry()).isEqualTo(0.4f, within(1e-6f));
        assertThat(b.getClear()).isEqualTo(0.5f, within(1e-6f));
        assertThat(b.getMobility()).isEqualTo(0.7f, within(1e-6f));
    }

    @Test
    void applyGpuBotProfileBiasesWeightsByNudgeButClampsAtOne() {
        BotState.Builder b = BotState.newBuilder().setId("B:x").setX(0).setY(0);
        Map<String, Double> profile = profile(0.3, 0.6, 0.4, 0.95, 0.5);
        Map<String, Double> nudges = new HashMap<>();
        nudges.put("forest", 0.10);   // 0.3 + 0.10 = 0.40
        nudges.put("clear", 0.20);    // 0.95 + 0.20 = 1.15 → clamps to 1.00

        svc.applyGpuBotProfile(b, profile, nudges);

        assertThat(b.getForest()).isEqualTo(0.40f, within(1e-6f));
        assertThat(b.getWater()).isEqualTo(0.60f, within(1e-6f));   // no nudge
        assertThat(b.getClear()).isEqualTo(1.00f, within(1e-6f));   // clamped
    }

    @Test
    void applyGpuBotProfileFallsBackToHalfWhenProfileMissingKey() {
        BotState.Builder b = BotState.newBuilder().setId("B:x").setX(0).setY(0);

        svc.applyGpuBotProfile(b, new HashMap<>(), null);

        assertThat(b.getForest()).isEqualTo(0.5f, within(1e-6f));
        assertThat(b.getMobility()).isEqualTo(0.5f, within(1e-6f));
    }

    // ---------- collectGpuTileDiffs ----------

    @Test
    void collectGpuTileDiffsReturnsEmptyOnNoChange() {
        int[][] tiles = { {0, 0}, {0, 0} };
        boolean[][] highways = { {false, false}, {false, false} };

        List<TileEdit> edits = svc.collectGpuTileDiffs(tiles, highways, tiles, highways, 100);

        assertThat(edits).isEmpty();
    }

    @Test
    void collectGpuTileDiffsEncodesHighwayAsTypeFour() {
        int[][] oldT = { {0, 0}, {0, 0} };
        boolean[][] oldH = { {false, false}, {false, false} };
        int[][] newT = { {1, 0}, {0, 0} };
        boolean[][] newH = { {false, true}, {false, false} };

        List<TileEdit> edits = svc.collectGpuTileDiffs(oldT, oldH, newT, newH, 100);

        assertThat(edits).hasSize(2);
        TileEdit forest = edits.stream().filter(e -> e.getX() == 0 && e.getY() == 0).findFirst().orElseThrow();
        assertThat(forest.getType()).isEqualTo(1);
        TileEdit highway = edits.stream().filter(e -> e.getX() == 1 && e.getY() == 0).findFirst().orElseThrow();
        assertThat(highway.getType()).isEqualTo(4);  // highway folded into type-4
    }

    @Test
    void collectGpuTileDiffsReturnsNullWhenExceedingMaxEdits() {
        int[][] oldT = { {0, 0, 0}, {0, 0, 0} };
        boolean[][] oldH = { {false, false, false}, {false, false, false} };
        int[][] newT = { {1, 1, 1}, {1, 1, 1} };
        boolean[][] newH = oldH;

        // 6 differences but max=2; the implementation accumulates and bails
        // out as soon as size > maxEdits (i.e. once we've collected 3 edits).
        List<TileEdit> edits = svc.collectGpuTileDiffs(oldT, oldH, newT, newH, 2);

        assertThat(edits).isNull();
    }

    @Test
    void collectGpuTileDiffsHandlesZeroSizedGrids() {
        int[][] empty = new int[0][];
        boolean[][] emptyH = new boolean[0][];

        assertThat(svc.collectGpuTileDiffs(empty, emptyH, empty, emptyH, 10)).isEmpty();
    }

    // ---------- applyTileEditsToCache ----------

    @Test
    void applyTileEditsToCacheWritesTypesAndHighwayBitInPlace() {
        int[][] tiles = { {0, 0}, {0, 0} };
        boolean[][] highways = { {false, false}, {false, false} };
        List<TileEdit> edits = List.of(
                TileEdit.newBuilder().setX(0).setY(0).setType(1).build(),  // forest
                TileEdit.newBuilder().setX(1).setY(0).setType(4).build()   // highway
        );

        svc.applyTileEditsToCache(tiles, highways, edits);

        assertThat(tiles[0][0]).isEqualTo(1);
        assertThat(highways[0][0]).isFalse();
        assertThat(tiles[0][1]).isEqualTo(0);   // highway clears underlying tile
        assertThat(highways[0][1]).isTrue();
    }

    @Test
    void applyTileEditsToCacheIgnoresOutOfBoundsAndNullList() {
        int[][] tiles = { {0, 0}, {0, 0} };
        boolean[][] highways = { {false, false}, {false, false} };
        List<TileEdit> edits = List.of(
                TileEdit.newBuilder().setX(-1).setY(0).setType(1).build(),
                TileEdit.newBuilder().setX(0).setY(99).setType(1).build(),
                TileEdit.newBuilder().setX(0).setY(0).setType(2).build()  // valid → water
        );

        svc.applyTileEditsToCache(tiles, highways, edits);
        svc.applyTileEditsToCache(tiles, highways, null);  // null = no-op

        assertThat(tiles[0][0]).isEqualTo(2);
    }

    // ---------- encode / decode bot motion state ----------

    @Test
    void encodeBotMotionStateBytesIsFortyByteLittleEndianBuffer() {
        BotMotionState state = new BotMotionState();
        state.lastX = 7;
        state.lastY = 9;
        state.headingX = 1;
        state.headingY = -1;
        state.reverseCooldownTicks = 3;
        state.plannedSteps.add(new int[] { 11, 12 });

        byte[] bytes = svc.encodeBotMotionStateBytes(state);

        assertThat(bytes).hasSize(40);
        // Layout: lastX | lastY | headingX | headingY | reverseCooldown | planSize
        // | step0X | step0Y | step1X | step1Y, all little-endian int32.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertThat(buf.getInt()).isEqualTo(7);
        assertThat(buf.getInt()).isEqualTo(9);
        assertThat(buf.getInt()).isEqualTo(1);
        assertThat(buf.getInt()).isEqualTo(-1);
        assertThat(buf.getInt()).isEqualTo(3);
        assertThat(buf.getInt()).isEqualTo(1);             // planSize
        assertThat(buf.getInt()).isEqualTo(11);
        assertThat(buf.getInt()).isEqualTo(12);
        assertThat(buf.getInt()).isEqualTo(Integer.MIN_VALUE);  // padded slot
        assertThat(buf.getInt()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void encodeBotMotionStateBytesMarshalsNullLastAsMinValue() {
        BotMotionState state = new BotMotionState();
        state.lastX = null;
        state.lastY = null;

        byte[] bytes = svc.encodeBotMotionStateBytes(state);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertThat(buf.getInt()).isEqualTo(Integer.MIN_VALUE);
        assertThat(buf.getInt()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void decodeBotMotionStateBytesRoundTripsAndSnapsCurrentToBotCoords() {
        BotMotionState original = new BotMotionState();
        original.lastX = 4;
        original.lastY = 5;
        original.headingX = -1;
        original.headingY = 0;
        original.reverseCooldownTicks = 2;
        original.plannedSteps.add(new int[] { 6, 7 });
        original.plannedSteps.add(new int[] { 8, 9 });

        byte[] bytes = svc.encodeBotMotionStateBytes(original);
        BotMotionState seed = new BotMotionState();
        BotMotionState decoded = svc.decodeBotMotionStateBytes(bytes, seed, 100, 200);

        assertThat(decoded).isSameAs(seed);
        assertThat(decoded.lastX).isEqualTo(4);
        assertThat(decoded.lastY).isEqualTo(5);
        assertThat(decoded.headingX).isEqualTo(-1);
        assertThat(decoded.headingY).isEqualTo(0);
        assertThat(decoded.reverseCooldownTicks).isEqualTo(2);
        assertThat(decoded.plannedSteps).hasSize(2);
        assertThat(decoded.plannedSteps.get(0)).containsExactly(6, 7);
        assertThat(decoded.plannedSteps.get(1)).containsExactly(8, 9);
        // currentX/currentY snap to the engine-declared bot position regardless
        // of what was encoded — the engine is authoritative on where the bot is.
        assertThat(decoded.currentX).isEqualTo(100);
        assertThat(decoded.currentY).isEqualTo(200);
    }

    @Test
    void decodeBotMotionStateBytesReturnsSeedWhenBufferTooSmall() {
        BotMotionState seed = new BotMotionState();
        seed.headingX = 42;  // sentinel so we can confirm the seed wasn't trampled

        BotMotionState decoded = svc.decodeBotMotionStateBytes(new byte[] {1, 2, 3}, seed, 0, 0);

        assertThat(decoded).isSameAs(seed);
        assertThat(decoded.headingX).isEqualTo(42);
    }

    @Test
    void decodeBotMotionStateBytesClampsNegativeCooldownToZero() {
        // Hand-craft a buffer with a negative cooldown to confirm the codec
        // floor-clamps. This guards against bad engine output corrupting the
        // motion state's reverse-cooldown invariant.
        byte[] bytes = new byte[40];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(Integer.MIN_VALUE);   // lastX
        buf.putInt(Integer.MIN_VALUE);   // lastY
        buf.putInt(0); buf.putInt(0);    // headingX/Y
        buf.putInt(-5);                  // reverseCooldownTicks → clamp to 0
        buf.putInt(0);                   // planSize
        buf.putInt(Integer.MIN_VALUE); buf.putInt(Integer.MIN_VALUE);
        buf.putInt(Integer.MIN_VALUE); buf.putInt(Integer.MIN_VALUE);

        BotMotionState decoded = svc.decodeBotMotionStateBytes(bytes, new BotMotionState(), 0, 0);

        assertThat(decoded.reverseCooldownTicks).isEqualTo(0);
    }

    // ---------- helpers ----------

    private static Map<String, Double> profile(double forest, double water, double industry, double clear, double mobility) {
        Map<String, Double> p = new HashMap<>();
        p.put("forest", forest);
        p.put("water", water);
        p.put("industry", industry);
        p.put("clear", clear);
        p.put("mobility", mobility);
        return p;
    }
}
