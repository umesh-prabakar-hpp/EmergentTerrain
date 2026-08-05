package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.UUID;

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
}
