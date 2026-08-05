package com.emergentterrain.controller;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.research.ResearchLifecycleExecutionRegistry;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.emergentterrain.researchcontract.ResearchExecutionContext;
import com.emergentterrain.researchcontract.ResearchLifecycleExecutionWindow;
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
    private final ResearchLifecycleExecutionRegistry lifecycleExecutions;

    public ResearchContextController(RoomRepository rooms, RoomBotRepository bots, RoomStateRepository states,
                                     ObjectMapper objectMapper,
                                     ResearchLifecycleExecutionRegistry lifecycleExecutions) {
        this.rooms = rooms;
        this.bots = bots;
        this.states = states;
        this.objectMapper = objectMapper;
        this.lifecycleExecutions = lifecycleExecutions;
    }

    @GetMapping("/api/v1/game/rooms/{roomId}/research-context")
    public ResponseEntity<ResearchExecutionContext> researchContext(@PathVariable("roomId") UUID roomId) {
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
        long gameTick = government.path("tick").asLong(0L);
        ResearchLifecycleExecutionWindow lifecycleWindow = lifecycleExecutions
                .windowForGameTick(roomId, gameTick).orElse(null);
        return ResponseEntity.ok(new ResearchExecutionContext(roomId, gameTick, Instant.now(), government,
                activeBots, lifecycleWindow == null ? null : lifecycleWindow.events().getLast(), lifecycleWindow));
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
