package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.research.UniversityService;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        ResearchGameContextProvider gameContext = mock(ResearchGameContextProvider.class);
        ResearchWorkerCheckpointRepository checkpoints = mock(ResearchWorkerCheckpointRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        List<RoomBot> activeBots = List.of(mock(RoomBot.class));

        when(room.getId()).thenReturn(roomId);
        when(rooms.findAll()).thenReturn(List.of(room));
        when(bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)).thenReturn(activeBots);
        when(gameContext.contextFor(room)).thenReturn(new ResearchGameContext(0L, null));
        when(checkpoints.findById(roomId)).thenReturn(Optional.empty());

        new ResearchWorkerScheduler(rooms, bots, gameContext, checkpoints, university)
                .runResearchTicks();

        verify(university).maybeTick(eq(roomId), eq(activeBots), eq(1L), isNull());
        verify(checkpoints).save(org.mockito.ArgumentMatchers.argThat(checkpoint ->
                checkpoint.getRoomId().equals(roomId) && checkpoint.getLastTick() == 1L));
    }

    @Test
    void resumesAboveItsDurableCheckpointAfterRestart() {
        RoomRepository rooms = mock(RoomRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        ResearchGameContextProvider gameContext = mock(ResearchGameContextProvider.class);
        ResearchWorkerCheckpointRepository checkpoints = mock(ResearchWorkerCheckpointRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        List<RoomBot> activeBots = List.of(mock(RoomBot.class));

        when(room.getId()).thenReturn(roomId);
        when(rooms.findAll()).thenReturn(List.of(room));
        when(bots.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE)).thenReturn(activeBots);
        when(gameContext.contextFor(room)).thenReturn(new ResearchGameContext(0L, null));
        when(checkpoints.findById(roomId)).thenReturn(Optional.of(new ResearchWorkerCheckpoint(roomId, 41L)));

        new ResearchWorkerScheduler(rooms, bots, gameContext, checkpoints, university)
                .runResearchTicks();

        verify(university).maybeTick(eq(roomId), eq(activeBots), eq(42L), isNull());
    }
}
