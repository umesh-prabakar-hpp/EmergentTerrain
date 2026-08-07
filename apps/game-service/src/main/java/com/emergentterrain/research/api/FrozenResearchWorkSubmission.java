package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchWorkRequest;

import java.util.Objects;

/** Immutable game-side handoff pairing already-frozen work with its source reservation. */
public record FrozenResearchWorkSubmission(ResearchWorkRequest request,
                                           ResearchWorkSourceReservation reservation) {
    public FrozenResearchWorkSubmission {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reservation, "reservation");
        if (!request.requestId().equals(reservation.requestId())) {
            throw new IllegalArgumentException("Submission reservation requestId must match the work request");
        }
    }
}
