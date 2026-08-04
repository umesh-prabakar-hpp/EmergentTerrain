package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchWorkerSchedulerTest {

    @Test
    void runsUniversityCycleFromPersistedRoomDataWithoutRoomService() {
        RoomRepository rooms = mock(RoomRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        RoomStateRepository states = mock(RoomStateRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        List<RoomBot> activeBots = List.of(mock(RoomBot.class));

        when(room.getId()).thenReturn(roomId);
        when(rooms.findAll()).thenReturn(List.of(room));
        when(bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)).thenReturn(activeBots);
        when(states.findByRoom(room)).thenReturn(Optional.empty());

        new ResearchWorkerScheduler(rooms, bots, states, university, new ObjectMapper())
                .runResearchTicks();

        verify(university).maybeTick(eq(roomId), eq(activeBots), eq(1L), isNull());
    }
}
