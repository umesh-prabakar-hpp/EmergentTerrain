package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotent acknowledgement for a durably accepted research work request. */
public record ResearchWorkReceipt(UUID requestId, String state, Instant acceptedAt) {

    public ResearchWorkReceipt {
        Objects.requireNonNull(requestId, "requestId");
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state is required");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
