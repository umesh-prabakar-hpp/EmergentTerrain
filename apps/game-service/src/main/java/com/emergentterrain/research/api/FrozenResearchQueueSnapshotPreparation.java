package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchWorkRequest;

import java.util.Objects;
import java.util.Optional;

/** Immutable game-side result of replaying a frozen conjecture queue snapshot. */
public record FrozenResearchQueueSnapshotPreparation(ResearchWorkRequest request,
                                                      Optional<ResearchWorkSourceReservation> sourceReservation) {
    public FrozenResearchQueueSnapshotPreparation {
        Objects.requireNonNull(request, "request");
        sourceReservation = sourceReservation == null ? Optional.empty() : sourceReservation;
        sourceReservation.ifPresent(reservation -> {
            if (!request.requestId().equals(reservation.requestId())) {
                throw new IllegalArgumentException("Snapshot reservation requestId must match the work request");
            }
        });
    }
}
