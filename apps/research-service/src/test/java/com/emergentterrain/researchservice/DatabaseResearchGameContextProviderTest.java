package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseResearchGameContextProviderTest {

    @Test
    void convertsTheCompatibilitySnapshotToTheResearchContext() {
        RoomStateRepository states = mock(RoomStateRepository.class);
        Room room = mock(Room.class);
        RoomState state = new RoomState();
        state.setGovernmentStateJson("{\"tick\":19}");
        when(states.findByRoom(room)).thenReturn(Optional.of(state));

        ResearchGameContext context = new DatabaseResearchGameContextProvider(states, new ObjectMapper())
                .contextFor(room);

        assertEquals(19L, context.gameTick());
        assertNotNull(context.governmentState());
        assertEquals(19L, context.governmentState().getTick());
    }
}
