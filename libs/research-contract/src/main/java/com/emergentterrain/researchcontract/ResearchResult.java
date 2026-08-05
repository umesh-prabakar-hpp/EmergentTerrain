package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable result emitted by research without prescribing source-application behavior. */
public record ResearchResult(UUID resultId, UUID requestId, UUID workspaceId, String outcome,
                             String summary, JsonNode findings, Instant completedAt) {
    public ResearchResult {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (outcome == null || outcome.isBlank() || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("outcome and summary are required");
        }
        findings = findings == null ? JsonNodeFactory.instance.objectNode() : findings;
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }
}
