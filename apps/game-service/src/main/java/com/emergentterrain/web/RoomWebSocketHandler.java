package com.emergentterrain.web;

import com.emergentterrain.service.RoomUpdateBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;

@Component
@Profile("!perf")
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private final RoomUpdateBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public RoomWebSocketHandler(RoomUpdateBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
            Object type = payload.get("type");
            if (!"subscribe".equals(type)) {
                return;
            }
            Object roomId = payload.get("roomId");
            if (roomId == null) {
                return;
            }
            UUID room = UUID.fromString(roomId.toString());
            broadcaster.register(session, room);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }
}
