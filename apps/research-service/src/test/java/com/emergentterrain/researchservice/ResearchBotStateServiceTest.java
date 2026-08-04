package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchBotStateServiceTest {

    @Test
    void seedsNewBotsButPreservesExistingResearchLifecycleOnLaterSnapshots() {
        ResearchBotStateRepository repository = mock(ResearchBotStateRepository.class);
        UUID roomId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        ResearchBotState existing = new ResearchBotState(existingId, roomId, "old", "research-owned", 4L, true);
        when(repository.findByRoomId(roomId)).thenReturn(List.of(existing));

        new ResearchBotStateService(repository).seedFromSnapshot(roomId,
                new ResearchGameContext(5L, null, List.of(
                        new ResearchBotSnapshot(existingId, "scientist", "game-lifecycle"),
                        new ResearchBotSnapshot(newId, "new", "new-lifecycle"))));

        assertEquals("research-owned", existing.getLifecycleJson());
        assertEquals(5L, existing.getLastSourceGameTick());
        verify(repository).save(argThat(state -> state.getBotId().equals(existingId)
                && state.isActive() && "research-owned".equals(state.getLifecycleJson())));
        verify(repository).save(argThat(state -> state.getBotId().equals(newId)
                && "new-lifecycle".equals(state.getLifecycleJson())));
    }
}
