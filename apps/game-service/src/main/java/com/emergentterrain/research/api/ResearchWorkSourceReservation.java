package com.emergentterrain.research.api;

import java.util.Objects;
import java.util.UUID;

/** Game-owned reservation metadata; it never crosses the research API boundary. */
public record ResearchWorkSourceReservation(UUID requestId, String reservationType, String sourceId) {
    public ResearchWorkSourceReservation {
        Objects.requireNonNull(requestId, "requestId");
        if (reservationType == null || reservationType.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("reservationType and sourceId are required");
        }
    }
}
