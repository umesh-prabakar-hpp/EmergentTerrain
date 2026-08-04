package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseResearchGameContextProviderTest {

    @Test
    void convertsTheCompatibilitySnapshotToTheResearchContext() {
        RoomStateRepository states = mock(RoomStateRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        Room room = mock(Room.class);
        RoomState state = new RoomState();
        RoomBot bot = mock(RoomBot.class);
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        state.setGovernmentStateJson("{\"tick\":19}");
        when(room.getId()).thenReturn(roomId);
        when(states.findByRoom(room)).thenReturn(Optional.of(state));
        when(bot.getId()).thenReturn(botId);
        when(bot.getType()).thenReturn("scientist");
        when(bot.getLifecycleJson()).thenReturn("{\"researchStatus\":\"QUALIFIED\"}");
        when(bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)).thenReturn(java.util.List.of(bot));

        ResearchGameContext context = new DatabaseResearchGameContextProvider(states, bots, new ObjectMapper())
                .contextFor(room);

        assertEquals(19L, context.gameTick());
        assertNotNull(context.governmentState());
        assertEquals(19L, context.governmentState().getTick());
        assertEquals(java.util.List.of(botId), context.activeBots().stream()
                .map(snapshot -> snapshot.botId()).toList());
    }
}
