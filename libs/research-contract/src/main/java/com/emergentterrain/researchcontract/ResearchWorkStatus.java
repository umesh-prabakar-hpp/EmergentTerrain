package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-only durable work lifecycle status; it exposes no source context, result content, or effects. */
public record ResearchWorkStatus(UUID requestId, String state, int attempts, Instant acceptedAt, Instant completedAt,
                                 Instant resultPublishedAt, Instant resultDeliveryClaimedAt,
                                 Instant resultDeliveryRejectedAt,
                                 String resultDeliveryRejection) {
    public ResearchWorkStatus {
        Objects.requireNonNull(requestId, "requestId");
        if (state == null || state.isBlank() || attempts < 0) {
            throw new IllegalArgumentException("state and a nonnegative attempt count are required");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        resultDeliveryRejection = resultDeliveryRejection == null ? "" : resultDeliveryRejection;
    }

    /** Source-compatible constructor for clients that do not yet observe an active delivery lease. */
    public ResearchWorkStatus(UUID requestId, String state, int attempts, Instant acceptedAt, Instant completedAt,
                              Instant resultPublishedAt, Instant resultDeliveryRejectedAt,
                              String resultDeliveryRejection) {
        this(requestId, state, attempts, acceptedAt, completedAt, resultPublishedAt, null,
                resultDeliveryRejectedAt, resultDeliveryRejection);
    }
}
