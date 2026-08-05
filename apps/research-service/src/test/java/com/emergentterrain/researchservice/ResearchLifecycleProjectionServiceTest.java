package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.emergentterrain.researchcontract.ResearchLifecycleExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchLifecycleProjectionServiceTest {

    @Test
    void seedsFromPostTickSnapshotThenProjectsTheNextObservedLegacyAdvance() throws Exception {
        ResearchLifecycleProjectionRepository projections = mock(ResearchLifecycleProjectionRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ResearchLifecycleProjectionService service = new ResearchLifecycleProjectionService(projections, mapper, true);
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        String source = "{\"ageTicks\":99,\"lifecycle\":\"JUVENILE\",\"reputation\":0.6}";
        ResearchBotSnapshot bot = new ResearchBotSnapshot(botId, "scientist", source);

        when(projections.findByRoomId(roomId)).thenReturn(List.of());
        var seeded = service.projectForParity(roomId, new ResearchGameContext(10L, null, List.of(bot),
                new ResearchLifecycleExecution(10L, true, 800L)));

        assertTrue(seeded.ready());
        assertEquals(source, seeded.bots().getFirst().lifecycleJson());
        verify(projections).save(org.mockito.ArgumentMatchers.any(ResearchLifecycleProjection.class));

        ResearchLifecycleProjection projection = new ResearchLifecycleProjection(botId, roomId, source, 10L);
        when(projections.findByRoomId(roomId)).thenReturn(List.of(projection));
        var predicted = service.projectForParity(roomId, new ResearchGameContext(11L, null, List.of(bot),
                new ResearchLifecycleExecution(11L, true, 800L)));

        var state = mapper.readTree(predicted.bots().getFirst().lifecycleJson());
        assertTrue(predicted.ready());
        assertEquals(100L, state.path("ageTicks").asLong());
        assertEquals("ADULT", state.path("lifecycle").asText());
        assertEquals(0.59996, state.path("reputation").asDouble(), 0.0000001);
        assertEquals(11L, projection.getLastObservedGameTick());
    }

    @Test
    void refusesToProjectWithoutExactTickExecutionMetadata() {
        ResearchLifecycleProjectionRepository projections = mock(ResearchLifecycleProjectionRepository.class);
        ResearchLifecycleProjectionService service = new ResearchLifecycleProjectionService(projections,
                new ObjectMapper(), true);
        ResearchGameContext context = new ResearchGameContext(11L, null, List.of(),
                new ResearchLifecycleExecution(10L, true, 800L));

        var result = service.projectForParity(UUID.randomUUID(), context);

        assertFalse(result.ready());
        verifyNoInteractions(projections);
    }

    @Test
    void preservesThePredictionWhenTheLegacyLifecycleWasSkipped() throws Exception {
        ResearchLifecycleProjectionRepository projections = mock(ResearchLifecycleProjectionRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ResearchLifecycleProjectionService service = new ResearchLifecycleProjectionService(projections, mapper, true);
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        String source = "{\"ageTicks\":100,\"lifecycle\":\"ADULT\",\"reputation\":0.6}";
        ResearchLifecycleProjection projection = new ResearchLifecycleProjection(botId, roomId, source, 10L);
        ResearchBotSnapshot bot = new ResearchBotSnapshot(botId, "scientist", source);
        when(projections.findByRoomId(roomId)).thenReturn(List.of(projection));

        var result = service.projectForParity(roomId, new ResearchGameContext(11L, null, List.of(bot),
                new ResearchLifecycleExecution(11L, false, null)));

        assertTrue(result.ready());
        assertEquals(100L, mapper.readTree(result.bots().getFirst().lifecycleJson()).path("ageTicks").asLong());
        assertEquals(0.6, mapper.readTree(result.bots().getFirst().lifecycleJson()).path("reputation").asDouble());
        assertEquals(11L, projection.getLastObservedGameTick());
    }

    @Test
    void projectsAnAdvancingTickOnlyOnceEvenWhenTheSnapshotIsPolledAgain() throws Exception {
        ResearchLifecycleProjectionRepository projections = mock(ResearchLifecycleProjectionRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ResearchLifecycleProjectionService service = new ResearchLifecycleProjectionService(projections, mapper, true);
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        String source = "{\"ageTicks\":99,\"lifecycle\":\"JUVENILE\",\"reputation\":0.6}";
        ResearchLifecycleProjection projection = new ResearchLifecycleProjection(botId, roomId, source, 10L);
        ResearchBotSnapshot bot = new ResearchBotSnapshot(botId, "scientist", source);
        when(projections.findByRoomId(roomId)).thenReturn(List.of(projection));
        ResearchGameContext context = new ResearchGameContext(11L, null, List.of(bot),
                new ResearchLifecycleExecution(11L, true, 800L));

        service.projectForParity(roomId, context);
        var repeated = service.projectForParity(roomId, context);

        assertEquals(100L, mapper.readTree(repeated.bots().getFirst().lifecycleJson()).path("ageTicks").asLong());
        assertEquals(11L, projection.getLastObservedGameTick());
    }
}
