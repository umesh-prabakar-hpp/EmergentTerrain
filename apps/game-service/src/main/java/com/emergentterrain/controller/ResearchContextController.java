package com.emergentterrain.controller;

import com.emergentterrain.domain.Room;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Game-owned context surface consumed by a separately deployed research worker. */
@RestController
public class ResearchContextController {

    private final RoomRepository rooms;
    private final RoomStateRepository states;
    private final ObjectMapper objectMapper;

    public ResearchContextController(RoomRepository rooms, RoomStateRepository states,
                                     ObjectMapper objectMapper) {
        this.rooms = rooms;
        this.states = states;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/game/rooms/{roomId}/research-context")
    public ResponseEntity<ResearchContextResponse> researchContext(@PathVariable UUID roomId) {
        Room room = rooms.findById(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        JsonNode government = states.findByRoom(room)
                .map(state -> parseGovernment(state.getGovernmentStateJson()))
                .orElseGet(() -> objectMapper.createObjectNode());
        return ResponseEntity.ok(new ResearchContextResponse(roomId, government.path("tick").asLong(0L),
                government));
    }

    private JsonNode parseGovernment(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            // A malformed legacy game-state snapshot must not make the boundary unavailable.
            return objectMapper.createObjectNode();
        }
    }

    public record ResearchContextResponse(UUID roomId, long gameTick, JsonNode governmentState) {
    }
}
