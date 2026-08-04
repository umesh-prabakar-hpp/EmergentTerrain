package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchBotReplicaServiceTest {

    @Test
    void importsCurrentBotsAndRetiresMissingBotsAtTheSameOrNewerTick() {
        ResearchBotReplicaRepository repository = mock(ResearchBotReplicaRepository.class);
        UUID roomId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        ResearchBotReplica stale = new ResearchBotReplica(staleId, roomId, "old", "{}", 7L, true);
        when(repository.findByRoomId(roomId)).thenReturn(List.of(stale));

        new ResearchBotReplicaService(repository).apply(roomId,
                new ResearchGameContext(8L, null,
                        List.of(new ResearchBotSnapshot(activeId, "scientist", "{\"generation\":2}"))));

        verify(repository).save(argThat(replica -> replica.getBotId().equals(activeId)
                && replica.isActive() && replica.getGameTick() == 8L));
        verify(repository).save(argThat(replica -> replica.getBotId().equals(staleId)
                && !replica.isActive() && replica.getGameTick() == 8L));
    }
}
