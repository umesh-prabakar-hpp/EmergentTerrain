package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
public class RoomUpdateBroadcaster {
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> sessionRoom = new ConcurrentHashMap<>();

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

    public void broadcastRoom(UUID roomId) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "type", "refresh",
                "roomId", roomId.toString()
            ));
        } catch (IOException e) {
            return;
        }
        TextMessage message = new TextMessage(payload);
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
