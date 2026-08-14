package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Research-owned comparison of matching frozen legacy-shadow and direct-sidecar observations.
 * It is audit evidence only and never represents a paper result, claim, or game consequence.
 */
public record ResearchComputationParity(UUID comparisonId, UUID draftId, UUID requestId, String comparisonVersion,
                                       String inputFingerprint, String status, JsonNode comparedEnvelope,
                                       Instant comparedAt) {
    public static final String NUMERIC_ENVELOPE_PARITY_V1 = "numeric-envelope-parity-v1";
    public static final String MATCHED = "MATCHED";
    public static final String MISMATCHED = "MISMATCHED";

    public ResearchComputationParity {
        Objects.requireNonNull(comparisonId, "comparisonId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        if (!NUMERIC_ENVELOPE_PARITY_V1.equals(comparisonVersion)
                || inputFingerprint == null || inputFingerprint.isBlank()
                || (!MATCHED.equals(status) && !MISMATCHED.equals(status))) {
            throw new IllegalArgumentException("A supported parity version, fingerprint, and status are required");
        }
        comparedEnvelope = comparedEnvelope == null ? JsonNodeFactory.instance.objectNode() : comparedEnvelope;
        comparedAt = comparedAt == null ? Instant.now() : comparedAt;
    }
}
