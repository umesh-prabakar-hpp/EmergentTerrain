package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchLifecycleExecutionRegistryTest {

    @Test
    void onlyReturnsExecutionMetadataForTheExactSnapshotTick() {
        ResearchLifecycleExecutionRegistry registry = new ResearchLifecycleExecutionRegistry();
        UUID roomId = UUID.randomUUID();

        registry.advanced(roomId, 12L, 1500L);

        assertTrue(registry.forGameTick(roomId, 12L).orElseThrow().advanced());
        assertFalse(registry.forGameTick(roomId, 13L).isPresent());
    }

    @Test
    void suppliesAContiguousReplayWindowForTheCurrentSnapshotTick() {
        ResearchLifecycleExecutionRegistry registry = new ResearchLifecycleExecutionRegistry();
        UUID roomId = UUID.randomUUID();

        registry.skipped(roomId, 10L);
        registry.advanced(roomId, 11L, 800L);

        var window = registry.windowForGameTick(roomId, 11L).orElseThrow();
        assertEquals(1L, window.firstSequence());
        assertEquals(2L, window.latestSequence());
        assertEquals(java.util.List.of(1L, 2L), window.events().stream()
                .map(event -> event.sequence()).toList());
    }
}
