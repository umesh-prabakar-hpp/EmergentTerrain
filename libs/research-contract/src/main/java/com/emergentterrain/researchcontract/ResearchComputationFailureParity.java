package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Research-owned comparison of controlled failure outcomes from matching frozen computations.
 * It records no stack trace or game/paper content and cannot authorize a result or retry policy.
 */
public record ResearchComputationFailureParity(UUID comparisonId, UUID draftId, UUID requestId,
                                              String comparisonVersion, String inputFingerprint, String status,
                                              String legacyOutcome, String directOutcome, Instant comparedAt) {
    public static final String FAILURE_SEMANTICS_PARITY_V1 = "failure-semantics-parity-v1";
    public static final String MATCHED = "MATCHED";
    public static final String MISMATCHED = "MISMATCHED";

    public ResearchComputationFailureParity {
        Objects.requireNonNull(comparisonId, "comparisonId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        if (!FAILURE_SEMANTICS_PARITY_V1.equals(comparisonVersion)
                || inputFingerprint == null || inputFingerprint.isBlank()
                || (!MATCHED.equals(status) && !MISMATCHED.equals(status))
                || legacyOutcome == null || legacyOutcome.isBlank()
                || directOutcome == null || directOutcome.isBlank()) {
            throw new IllegalArgumentException("A supported failure-parity version, binding, status, and outcomes are required");
        }
        comparedAt = comparedAt == null ? Instant.now() : comparedAt;
    }
}
