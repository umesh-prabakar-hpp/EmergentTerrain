package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable observation of a conjecture the legacy game cycle has already consumed. */
public record ResearchConjectureConsumptionEvent(UUID eventId, UUID roomId, long sequence, long gameTick,
                                                 String authorBotId, String domain, String sourceId,
                                                 String selectionKind, Instant observedAt) {
    public ResearchConjectureConsumptionEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(authorBotId, "authorBotId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(selectionKind, "selectionKind");
        Objects.requireNonNull(observedAt, "observedAt");
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (gameTick < 0L) {
            throw new IllegalArgumentException("gameTick must not be negative");
        }
    }
}
