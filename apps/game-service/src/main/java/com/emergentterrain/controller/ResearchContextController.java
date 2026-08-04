package com.emergentterrain.controller;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.emergentterrain.researchcontract.ResearchExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

/** Game-owned context surface consumed by a separately deployed research worker. */
@RestController
public class ResearchContextController {

    private final RoomRepository rooms;
    private final RoomBotRepository bots;
    private final RoomStateRepository states;
    private final ObjectMapper objectMapper;

    public ResearchContextController(RoomRepository rooms, RoomBotRepository bots, RoomStateRepository states,
                                     ObjectMapper objectMapper) {
        this.rooms = rooms;
        this.bots = bots;
        this.states = states;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/game/rooms/{roomId}/research-context")
    public ResponseEntity<ResearchExecutionContext> researchContext(@PathVariable UUID roomId) {
        Room room = rooms.findById(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        JsonNode government = states.findByRoom(room)
                .map(state -> parseGovernment(state.getGovernmentStateJson()))
                .orElseGet(() -> objectMapper.createObjectNode());
        List<ResearchBotSnapshot> activeBots = bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)
                .stream()
                .map(bot -> new ResearchBotSnapshot(bot.getId(), bot.getType(), bot.getLifecycleJson()))
                .toList();
        return ResponseEntity.ok(new ResearchExecutionContext(roomId, government.path("tick").asLong(0L),
                Instant.now(), government, activeBots));
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
}
