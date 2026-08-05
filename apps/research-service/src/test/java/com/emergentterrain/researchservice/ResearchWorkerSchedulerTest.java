package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.research.UniversityService;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;
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
        ResearchGameContextProvider gameContext = mock(ResearchGameContextProvider.class);
        ResearchBotReplicaService botReplicas = mock(ResearchBotReplicaService.class);
        ResearchBotStateService botStates = mock(ResearchBotStateService.class);
        ResearchLifecycleShadowService lifecycleShadow = mock(ResearchLifecycleShadowService.class);
        ResearchLifecycleProjectionService lifecycleProjector = mock(ResearchLifecycleProjectionService.class);
        ResearchWorkerCheckpointRepository checkpoints = mock(ResearchWorkerCheckpointRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        RoomBot activeBot = activeBot(room, roomId);
        List<RoomBot> activeBots = List.of(activeBot);
        UUID botId = activeBot.getId();

        when(room.getId()).thenReturn(roomId);
        when(rooms.findAll()).thenReturn(List.of(room));
        when(bots.findAllById(List.of(botId))).thenReturn(activeBots);
        when(gameContext.contextFor(room)).thenReturn(new ResearchGameContext(0L, null,
                List.of(new ResearchBotSnapshot(botId, "scientist", "{}"))));
        when(checkpoints.findById(roomId)).thenReturn(Optional.empty());

        new ResearchWorkerScheduler(rooms, bots, gameContext, botReplicas, botStates, lifecycleShadow, lifecycleProjector,
                checkpoints, university, false, 1000L)
                .runResearchTicks();

        verify(university).maybeTick(eq(roomId), eq(activeBots), eq(1L), isNull());
        verify(botReplicas).apply(eq(roomId), org.mockito.ArgumentMatchers.any());
        verify(botStates).seedFromSnapshot(eq(roomId), org.mockito.ArgumentMatchers.any());
        verify(lifecycleShadow).compareAgainstGameSnapshot(eq(roomId), eq(1L),
                eq(List.of(new ResearchBotSnapshot(botId, "scientist", "{}"))));
        verify(checkpoints).save(org.mockito.ArgumentMatchers.argThat(checkpoint ->
                checkpoint.getRoomId().equals(roomId) && checkpoint.getLastTick() == 1L));
    }

    @Test
    void resumesAboveItsDurableCheckpointAfterRestart() {
        RoomRepository rooms = mock(RoomRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        ResearchGameContextProvider gameContext = mock(ResearchGameContextProvider.class);
        ResearchBotReplicaService botReplicas = mock(ResearchBotReplicaService.class);
        ResearchBotStateService botStates = mock(ResearchBotStateService.class);
        ResearchLifecycleShadowService lifecycleShadow = mock(ResearchLifecycleShadowService.class);
        ResearchLifecycleProjectionService lifecycleProjector = mock(ResearchLifecycleProjectionService.class);
        ResearchWorkerCheckpointRepository checkpoints = mock(ResearchWorkerCheckpointRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        RoomBot activeBot = activeBot(room, roomId);
        List<RoomBot> activeBots = List.of(activeBot);
        UUID botId = activeBot.getId();

        when(room.getId()).thenReturn(roomId);
        when(rooms.findAll()).thenReturn(List.of(room));
        when(bots.findAllById(List.of(botId))).thenReturn(activeBots);
        when(gameContext.contextFor(room)).thenReturn(new ResearchGameContext(0L, null,
                List.of(new ResearchBotSnapshot(botId, "scientist", "{}"))));
        when(checkpoints.findById(roomId)).thenReturn(Optional.of(new ResearchWorkerCheckpoint(roomId, 41L)));

        new ResearchWorkerScheduler(rooms, bots, gameContext, botReplicas, botStates, lifecycleShadow, lifecycleProjector,
                checkpoints, university, false, 1000L)
                .runResearchTicks();

        verify(university).maybeTick(eq(roomId), eq(activeBots), eq(42L), isNull());
    }

    private static RoomBot activeBot(Room room, UUID roomId) {
        RoomBot bot = mock(RoomBot.class);
        UUID botId = UUID.randomUUID();
        when(bot.getId()).thenReturn(botId);
        when(bot.getRoom()).thenReturn(room);
        when(bot.getStatus()).thenReturn(RoomBot.BotStatus.ACTIVE);
        when(room.getId()).thenReturn(roomId);
        return bot;
    }

    @Test
    void shadowOnlyRecordsTheObservationWithoutCallingTheLegacyUniversity() {
        RoomRepository rooms = mock(RoomRepository.class);
        RoomBotRepository bots = mock(RoomBotRepository.class);
        ResearchGameContextProvider gameContext = mock(ResearchGameContextProvider.class);
        ResearchBotReplicaService botReplicas = mock(ResearchBotReplicaService.class);
        ResearchBotStateService botStates = mock(ResearchBotStateService.class);
        ResearchLifecycleShadowService lifecycleShadow = mock(ResearchLifecycleShadowService.class);
        ResearchLifecycleProjectionService lifecycleProjector = mock(ResearchLifecycleProjectionService.class);
        ResearchWorkerCheckpointRepository checkpoints = mock(ResearchWorkerCheckpointRepository.class);
        UniversityService university = mock(UniversityService.class);
        Room room = mock(Room.class);
        UUID roomId = UUID.randomUUID();
        RoomBot activeBot = activeBot(room, roomId);
        UUID botId = activeBot.getId();

        when(rooms.findAll()).thenReturn(List.of(room));
        when(gameContext.contextFor(room)).thenReturn(new ResearchGameContext(0L, null,
                List.of(new ResearchBotSnapshot(botId, "scientist", "{}"))));
        when(checkpoints.findById(roomId)).thenReturn(Optional.empty());

        new ResearchWorkerScheduler(rooms, bots, gameContext, botReplicas, botStates, lifecycleShadow, lifecycleProjector,
                checkpoints, university, true, 1000L).runResearchTicks();

        verify(lifecycleShadow).compareAgainstGameSnapshot(eq(roomId), eq(1L),
                eq(List.of(new ResearchBotSnapshot(botId, "scientist", "{}"))));
        verify(checkpoints).save(org.mockito.ArgumentMatchers.argThat(checkpoint ->
                checkpoint.getRoomId().equals(roomId) && checkpoint.getLastTick() == 1L));
        org.mockito.Mockito.verifyNoInteractions(university);
        org.mockito.Mockito.verifyNoInteractions(bots);
    }
}
