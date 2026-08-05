package com.emergentterrain.controller;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.research.ResearchLifecycleExecutionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchContextControllerTest {

    @Test
    void returnsTheLatestPersistedGovernmentSnapshot() {
        RoomRepository rooms = mock(RoomRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        RoomStateRepository states = mock(RoomStateRepository.class);
        Room room = mock(Room.class);
        RoomBot bot = mock(RoomBot.class);
        RoomState state = new RoomState();
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        state.setGovernmentStateJson("{\"tick\":27,\"constitutionVersion\":\"v1\"}");
        when(rooms.findById(roomId)).thenReturn(Optional.of(room));
        when(states.findByRoom(room)).thenReturn(Optional.of(state));
        when(bot.getId()).thenReturn(botId);
        when(bot.getType()).thenReturn("scientist");
        when(bot.getLifecycleJson()).thenReturn("{\"researchStatus\":\"QUALIFIED\"}");
        when(bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)).thenReturn(java.util.List.of(bot));

        ResearchLifecycleExecutionRegistry lifecycleExecutions = new ResearchLifecycleExecutionRegistry();
        lifecycleExecutions.advanced(roomId, 27L, 1200L);
        var response = new ResearchContextController(rooms, bots, states, new ObjectMapper(), lifecycleExecutions)
                .researchContext(roomId);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(roomId, response.getBody().roomId());
        assertEquals(27L, response.getBody().gameTick());
        assertEquals("v1", response.getBody().governmentState().path("constitutionVersion").asText());
        assertEquals(java.util.List.of(botId), response.getBody().activeBots().stream()
                .map(snapshot -> snapshot.botId()).toList());
        assertNotNull(response.getBody().lifecycleExecution());
        assertTrue(response.getBody().lifecycleExecution().advanced());
        assertEquals(1200L, response.getBody().lifecycleExecution().adultToElder());
    }
}
