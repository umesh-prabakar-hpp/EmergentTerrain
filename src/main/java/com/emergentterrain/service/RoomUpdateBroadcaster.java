package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emergentterrain.web.RoomProposalInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
public class RoomUpdateBroadcaster {
    private static final long TILE_TRANSITION_DURATION_MS = 900L;

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> sessionRoom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RoomVisualState> lastVisualState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<Map<String, Object>>> activeTileTransitions = new ConcurrentHashMap<>();

    public RoomUpdateBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session, UUID roomId) {
        roomSessions.computeIfAbsent(roomId, id -> new CopyOnWriteArraySet<>()).add(session);
        sessionRoom.put(session.getId(), roomId);
    }

    public void unregister(WebSocketSession session) {
        UUID roomId = sessionRoom.remove(session.getId());
        if (roomId == null) {
            return;
        }
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public void broadcastRoom(RoomVisualState visualState) {
        if (visualState == null || visualState.getRoomId() == null) {
            return;
        }
        UUID roomId = visualState.getRoomId();
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        RoomVisualState previous = lastVisualState.put(roomId, visualState);
        if (previous != null) {
            broadcastProposalEvents(sessions, roomId, previous, visualState);
            broadcastTileTransitionStart(sessions, roomId, previous, visualState);
        }
        broadcastTileTransitionProgress(sessions, roomId);
        sendPayload(sessions, Map.of(
            "type", "refresh",
            "roomId", roomId.toString(),
            "stateHash", visualState.getStateHash()
        ));
    }

    public void broadcastLlmToken(UUID roomId, String streamId, String kind, String token) {
        if (roomId == null) return;
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;
        sendPayload(sessions, Map.of(
            "type", "llm_token",
            "roomId", roomId.toString(),
            "streamId", streamId,
            "kind", kind,
            "token", token
        ));
    }

    public void broadcastLlmDone(UUID roomId, String streamId, String kind) {
        if (roomId == null) return;
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;
        sendPayload(sessions, Map.of(
            "type", "llm_done",
            "roomId", roomId.toString(),
            "streamId", streamId,
            "kind", kind
        ));
    }

    public void broadcastGovernmentUpdate(UUID roomId) {
        if (roomId == null) return;
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;
        sendPayload(sessions, Map.of(
            "type", "government_updated",
            "roomId", roomId.toString()
        ));
    }

    private void broadcastProposalEvents(Set<WebSocketSession> sessions,
                                         UUID roomId,
                                         RoomVisualState previous,
                                         RoomVisualState current) {
        Map<String, RoomProposalInfo> oldById = proposalsById(previous.getProposals());
        for (RoomProposalInfo proposal : current.getProposals()) {
            RoomProposalInfo old = oldById.get(proposal.getId());
            if (old == null) {
                sendPayload(sessions, Map.of(
                    "type", "proposal_created",
                    "roomId", roomId.toString(),
                    "proposal", proposal
                ));
                continue;
            }
            boolean votesChanged = old.getApprovals() != proposal.getApprovals()
                || old.getRejections() != proposal.getRejections()
                || !safeEquals(old.getStatus(), proposal.getStatus());
            if (!votesChanged) {
                continue;
            }
            String nextStatus = proposal.getStatus() == null ? "" : proposal.getStatus();
            if ("APPLIED".equals(nextStatus) || "REJECTED".equals(nextStatus)) {
                sendPayload(sessions, Map.of(
                    "type", "proposal_resolved",
                    "roomId", roomId.toString(),
                    "proposal", proposal
                ));
            } else {
                sendPayload(sessions, Map.of(
                    "type", "proposal_vote_delta",
                    "roomId", roomId.toString(),
                    "proposal", proposal
                ));
            }
        }
    }

    private void broadcastTileTransitionStart(Set<WebSocketSession> sessions,
                                              UUID roomId,
                                              RoomVisualState previous,
                                              RoomVisualState current) {
        if (safeEquals(previous.getStateHash(), current.getStateHash())) {
            return;
        }
        List<Map<String, Object>> transitions = diffTileTransitions(previous, current);
        if (transitions.isEmpty()) {
            return;
        }
        activeTileTransitions.put(roomId, transitions);
        sendPayload(sessions, Map.of(
            "type", "tile_transition_started",
            "roomId", roomId.toString(),
            "durationMs", TILE_TRANSITION_DURATION_MS,
            "transitions", transitions
        ));
    }

    private void broadcastTileTransitionProgress(Set<WebSocketSession> sessions, UUID roomId) {
        List<Map<String, Object>> transitions = activeTileTransitions.get(roomId);
        if (transitions == null || transitions.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> progress = new ArrayList<>();
        for (Map<String, Object> transition : transitions) {
            long startedAtMs = ((Number) transition.get("startedAtMs")).longValue();
            double value = Math.max(0d, Math.min(1d, (nowMs - startedAtMs) / (double) TILE_TRANSITION_DURATION_MS));
            Map<String, Object> enriched = new HashMap<>(transition);
            enriched.put("progress", value);
            progress.add(enriched);
            if (value < 1d) {
                active.add(transition);
            }
        }
        if (active.isEmpty()) {
            activeTileTransitions.remove(roomId);
        } else {
            activeTileTransitions.put(roomId, active);
        }
        sendPayload(sessions, Map.of(
            "type", "tile_transition_progress",
            "roomId", roomId.toString(),
            "transitions", progress
        ));
    }

    private List<Map<String, Object>> diffTileTransitions(RoomVisualState previous, RoomVisualState current) {
        List<Map<String, Object>> transitions = new ArrayList<>();
        int[][] oldTiles = previous.getTiles();
        int[][] newTiles = current.getTiles();
        boolean[][] oldHighways = previous.getHighways();
        boolean[][] newHighways = current.getHighways();
        if (oldTiles == null || newTiles == null || oldTiles.length == 0 || newTiles.length == 0) {
            return transitions;
        }
        int height = Math.min(oldTiles.length, newTiles.length);
        int width = Math.min(oldTiles[0].length, newTiles[0].length);
        long nowMs = System.currentTimeMillis();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean oldHighway = oldHighways != null && oldHighways.length > y && oldHighways[y].length > x && oldHighways[y][x];
                boolean newHighway = newHighways != null && newHighways.length > y && newHighways[y].length > x && newHighways[y][x];
                if (oldTiles[y][x] == newTiles[y][x] && oldHighway == newHighway) {
                    continue;
                }
                transitions.add(Map.of(
                    "x", x,
                    "y", y,
                    "fromTile", oldTiles[y][x],
                    "toTile", newTiles[y][x],
                    "fromHighway", oldHighway,
                    "toHighway", newHighway,
                    "startedAtMs", nowMs,
                    "durationMs", TILE_TRANSITION_DURATION_MS,
                    "phase", "committed"
                ));
            }
        }
        return transitions;
    }

    private Map<String, RoomProposalInfo> proposalsById(List<RoomProposalInfo> proposals) {
        Map<String, RoomProposalInfo> byId = new HashMap<>();
        if (proposals == null) {
            return byId;
        }
        for (RoomProposalInfo proposal : proposals) {
            if (proposal.getId() != null) {
                byId.put(proposal.getId(), proposal);
            }
        }
        return byId;
    }

    private boolean safeEquals(Object left, Object right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private void sendPayload(Set<WebSocketSession> sessions, Map<String, Object> payload) {
        String raw;
        try {
            raw = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }
        TextMessage message = new TextMessage(raw);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                } else {
                    unregister(session);
                }
            } catch (IOException ignored) {
                unregister(session);
            }
        }
    }
}
