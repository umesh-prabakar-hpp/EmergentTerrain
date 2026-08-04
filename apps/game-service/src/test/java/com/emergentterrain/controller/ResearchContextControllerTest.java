package com.emergentterrain.controller;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
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
        RoomStateRepository states = mock(RoomStateRepository.class);
        Room room = mock(Room.class);
        RoomState state = new RoomState();
        UUID roomId = UUID.randomUUID();
        state.setGovernmentStateJson("{\"tick\":27,\"constitutionVersion\":\"v1\"}");
        when(rooms.findById(roomId)).thenReturn(Optional.of(room));
        when(states.findByRoom(room)).thenReturn(Optional.of(state));

        var response = new ResearchContextController(rooms, states, new ObjectMapper())
                .researchContext(roomId);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(roomId, response.getBody().roomId());
        assertEquals(27L, response.getBody().gameTick());
        assertEquals("v1", response.getBody().governmentState().path("constitutionVersion").asText());
    }
}
