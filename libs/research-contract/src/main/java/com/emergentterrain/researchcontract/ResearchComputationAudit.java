package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Research-owned shadow observation of one frozen computation input. It is not a paper, result
 * projection, effect recommendation, or authorization to narrate or publish.
 */
public record ResearchComputationAudit(UUID auditId, UUID draftId, UUID requestId, String auditVersion,
                                      String inputFingerprint, JsonNode observedOutput, Instant observedAt) {
    public static final String SIDECAR_SHADOW_AUDIT_V1 = "sidecar-shadow-audit-v1";

    public ResearchComputationAudit {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        if (!SIDECAR_SHADOW_AUDIT_V1.equals(auditVersion) || inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("A supported audit version and input fingerprint are required");
        }
        observedOutput = observedOutput == null ? JsonNodeFactory.instance.objectNode() : observedOutput;
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
