package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A research-owned, direct sidecar observation for a fully frozen computation input.
 * It is parity evidence only: it carries no paper, claim, result-delivery, or game authority.
 */
public record ResearchComputationRun(UUID runId, UUID draftId, UUID requestId, String runnerVersion,
                                    String inputFingerprint, JsonNode observedOutput, Instant observedAt) {
    public static final String DIRECT_SIDECAR_SCAN_V1 = "direct-sidecar-scan-v1";

    public ResearchComputationRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        if (!DIRECT_SIDECAR_SCAN_V1.equals(runnerVersion)
                || inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("A supported runner version and input fingerprint are required");
        }
        observedOutput = observedOutput == null ? JsonNodeFactory.instance.objectNode() : observedOutput;
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
