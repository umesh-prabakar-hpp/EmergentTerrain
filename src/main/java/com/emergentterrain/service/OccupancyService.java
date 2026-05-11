package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomParticipantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2.5 carve: occupancy primitives + viewport math lifted out of
 * {@link RoomService}.
 *
 * Surface:
 * <ul>
 *   <li>{@link #computeViewBounds} / {@link #inView} / {@link #withinRadius}
 *       — pure viewport geometry, now the single source of truth for the
 *       fixed-size view window {@code VISIBLE_WIDTH × VISIBLE_HEIGHT}.</li>
 *   <li>{@link #canOccupy} — density rule check given a precomputed occupant
 *       list. Caller (RoomService) supplies the list and the tunables, so
 *       the service stays free of the {@code roomStateCache} entanglement.</li>
 *   <li>{@link #buildOccupancySnapshot} — assembles the
 *       {@code List<OccupantSnapshot>} from active participants + bots.</li>
 *   <li>{@link #readOccupancyJson} — Jackson codec for the persisted
 *       {@code occupancyJson} string.</li>
 * </ul>
 *
 * Scope deliberately narrower than the design doc's enumerated list:
 * {@code getCachedOccupancy} and {@code refreshOccupancySnapshot} stay in
 * {@link RoomService} because they read/write {@code roomStateCache} (whose
 * other fields — tiles, highways, road pressure, state hash, dirty flag —
 * are owned by RoomService's broader state-management responsibilities and
 * have nothing to do with occupancy). Pulling them out would require
 * threading the cache map across services. Same pattern as the
 * {@code RoomStateOrchestrator} carve, which deferred the deserialization
 * side for the same reason. RoomService now keeps those two methods as thin
 * cache orchestrators that delegate their primitives to this service.
 *
 * Pure refactor. No behavioural change. The viewport-clamp arithmetic in
 * {@link #computeViewBounds} mirrors the pre-carve implementation byte-for-
 * byte, so visual responses and proposal-anchor calculations are identical.
 */
@Service
public class OccupancyService {

    private static final int VISIBLE_WIDTH = 48;
    private static final int VISIBLE_HEIGHT = 36;

    private final RoomParticipantRepository participantRepository;
    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;

    public OccupancyService(RoomParticipantRepository participantRepository,
                            RoomBotRepository botRepository,
                            ObjectMapper objectMapper) {
        this.participantRepository = participantRepository;
        this.botRepository = botRepository;
        this.objectMapper = objectMapper;
    }

    /** Width of the fixed-size view window in tiles. */
    public int visibleWidth() {
        return VISIBLE_WIDTH;
    }

    /** Height of the fixed-size view window in tiles. */
    public int visibleHeight() {
        return VISIBLE_HEIGHT;
    }

    /**
     * Clamped viewport bounds for a window of {@code (VISIBLE_WIDTH ×
     * VISIBLE_HEIGHT)} centred on {@code (cx, cy)}. Returns
     * {@code {viewX, viewY, viewW, viewH}}. If the world is smaller than the
     * window, the window shrinks to the world; otherwise it slides to stay
     * inside the world.
     */
    public int[] computeViewBounds(int cx, int cy, int[][] tiles) {
        int h = tiles.length;
        int w = tiles[0].length;
        int viewW = Math.min(w, VISIBLE_WIDTH);
        int viewH = Math.min(h, VISIBLE_HEIGHT);
        int viewX = Math.max(0, Math.min(w - viewW, cx - viewW / 2));
        int viewY = Math.max(0, Math.min(h - viewH, cy - viewH / 2));
        return new int[] { viewX, viewY, viewW, viewH };
    }

    /** True when {@code (x, y)} lies inside the half-open viewport rectangle. */
    public boolean inView(int viewX, int viewY, int viewW, int viewH, int x, int y) {
        return x >= viewX && x < viewX + viewW && y >= viewY && y < viewY + viewH;
    }

    /** True when {@code (x, y)} is within {@code radius} tiles of {@code (cx, cy)} (Euclidean). */
    public boolean withinRadius(int cx, int cy, int x, int y, int radius) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Density rule check: returns {@code true} when fewer than
     * {@code maxOccupants} entries from {@code occupants} fall inside a
     * {@code densityAreaSize × densityAreaSize} box anchored at {@code (x,
     * y)}. Entries whose id equals or ends-with {@code ignoreId} are
     * skipped, so a bot or participant testing its own next-step doesn't
     * see itself as a blocker. {@code ignoreId} may be empty or null.
     */
    public boolean canOccupy(int x, int y, String ignoreId,
                             List<OccupantSnapshot> occupants,
                             int densityAreaSize,
                             int maxOccupants) {
        int half = densityAreaSize / 2;
        int minX = x - half;
        int maxX = x + half - 1;
        int minY = y - half;
        int maxY = y + half - 1;
        int count = 0;
        for (OccupantSnapshot occ : occupants) {
            if (occ == null) {
                continue;
            }
            String id = occ.getId();
            if (id != null && ignoreId != null && !ignoreId.isBlank()) {
                if (id.equals(ignoreId) || id.endsWith(ignoreId)) {
                    continue;
                }
            }
            int ox = occ.getX();
            int oy = occ.getY();
            if (ox >= minX && ox <= maxX && oy >= minY && oy <= maxY) {
                count++;
            }
        }
        return count < maxOccupants;
    }

    /**
     * Builds the live occupancy snapshot for a room: active participants
     * (prefixed {@code P:}) followed by all bots (prefixed {@code B:}).
     * Entries with missing or negative coordinates are skipped.
     */
    public List<OccupantSnapshot> buildOccupancySnapshot(Room room) {
        List<OccupantSnapshot> occupants = new ArrayList<>();
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getId() == null || participant.getX() == null || participant.getY() == null) {
                continue;
            }
            if (participant.getX() < 0 || participant.getY() < 0) {
                continue;
            }
            occupants.add(new OccupantSnapshot("P:" + participant.getId(), participant.getX(), participant.getY()));
        }
        for (RoomBot bot : botRepository.findByRoom(room)) {
            if (bot.getId() == null || bot.getX() == null || bot.getY() == null) {
                continue;
            }
            if (bot.getX() < 0 || bot.getY() < 0) {
                continue;
            }
            occupants.add(new OccupantSnapshot("B:" + bot.getId(), bot.getX(), bot.getY()));
        }
        return occupants;
    }

    /**
     * Decodes a persisted {@code occupancyJson} string. Null/blank input or
     * any JSON parse error returns an empty mutable list — callers treat a
     * malformed snapshot as "no occupants known yet" and rebuild on the
     * next refresh, the same behaviour as before this carve.
     */
    public List<OccupantSnapshot> readOccupancyJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<OccupantSnapshot>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }
}
