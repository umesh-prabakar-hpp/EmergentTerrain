package com.emergentterrain.researchservice;

import com.emergentterrain.domain.RoomBot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchLifecycleShadowServiceTest {

    @Test
    void recordsParityForMatchingPersistedLifecycleFields() {
        ResearchBotStateRepository states = mock(ResearchBotStateRepository.class);
        ResearchLifecycleShadowRecordRepository records = mock(ResearchLifecycleShadowRecordRepository.class);
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        String lifecycle = "{\"ageTicks\":101,\"lifecycle\":\"ADULT\",\"researchStatus\":\"QUALIFIED\","
                + "\"publishedCount\":2,\"rejectedCount\":1,\"reputation\":0.4,\"skepticismLevel\":0.6,"
                + "\"lastCompetencyTick\":90}";
        ResearchBotState state = new ResearchBotState(botId, roomId, "scientist", lifecycle, 10L, true);
        RoomBot legacy = mock(RoomBot.class);
        when(legacy.getId()).thenReturn(botId);
        when(legacy.getLifecycleJson()).thenReturn(lifecycle);
        when(states.findByRoomId(roomId)).thenReturn(List.of(state));

        new ResearchLifecycleShadowService(states, records, new ObjectMapper(), true)
                .compareBeforeLegacyCycle(roomId, 11L, List.of(legacy));

        verify(records).save(argThat(record -> record.getRoomId().equals(roomId)
                && record.getGameTick() == 11L && record.getComparedBots() == 1
                && record.getMatchingBots() == 1 && record.getMismatchingBots() == 0));
    }
}
