package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned, read-only game snapshot consumed by the research worker. */
public record ResearchExecutionContext(
        UUID roomId,
        long gameTick,
        Instant capturedAt,
        JsonNode governmentState,
        List<ResearchBotSnapshot> activeBots) {

    public ResearchExecutionContext {
        activeBots = activeBots == null ? List.of() : List.copyOf(activeBots);
    }
}
