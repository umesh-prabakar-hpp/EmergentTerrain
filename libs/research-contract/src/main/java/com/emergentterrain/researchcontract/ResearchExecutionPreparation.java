package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Frozen, source-agnostic readiness record between draft selection and a future computation
 * executor.  It intentionally contains no result, claim, or authorization to compute.
 */
public record ResearchExecutionPreparation(UUID preparationId, UUID draftId, UUID requestId,
                                           String preparationVersion, String executionKind,
                                           String readiness, String inputFingerprint,
                                           JsonNode canonicalInput, Instant preparedAt) {
    public static final String FROZEN_EXECUTION_PREPARATION_V1 = "frozen-execution-preparation-v1";
    public static final String AWAITING_COMPUTATION_INPUTS = "AWAITING_COMPUTATION_INPUTS";
    public static final String FORMULATION_FROZEN_AWAITING_COMPUTATION = "FORMULATION_FROZEN_AWAITING_COMPUTATION";
    public static final String PREPARED_NO_COMPUTATION = "PREPARED_NO_COMPUTATION";

    public ResearchExecutionPreparation {
        Objects.requireNonNull(preparationId, "preparationId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        if (!FROZEN_EXECUTION_PREPARATION_V1.equals(preparationVersion)) {
            throw new IllegalArgumentException("Unsupported execution preparation version: " + preparationVersion);
        }
        if (executionKind == null || executionKind.isBlank() || inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("executionKind and inputFingerprint are required");
        }
        if (!AWAITING_COMPUTATION_INPUTS.equals(readiness)
                && !FORMULATION_FROZEN_AWAITING_COMPUTATION.equals(readiness)
                && !PREPARED_NO_COMPUTATION.equals(readiness)) {
            throw new IllegalArgumentException("Unsupported execution preparation readiness: " + readiness);
        }
        canonicalInput = canonicalInput == null ? JsonNodeFactory.instance.objectNode() : canonicalInput;
        preparedAt = preparedAt == null ? Instant.now() : preparedAt;
    }
}
