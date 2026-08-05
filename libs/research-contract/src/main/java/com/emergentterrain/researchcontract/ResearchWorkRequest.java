package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request submitted by an external source for autonomous research processing.
 *
 * <p>Identifiers are opaque to research. The source revision provides ordering/idempotency context
 * without encoding source-domain terminology.</p>
 */
public record ResearchWorkRequest(
        UUID requestId,
        UUID workspaceId,
        String workType,
        long sourceRevision,
        Instant requestedAt,
        List<ResearchSubject> subjects,
        JsonNode contextSnapshot) {

    public ResearchWorkRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (workType == null || workType.isBlank()) {
            throw new IllegalArgumentException("workType is required");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        contextSnapshot = contextSnapshot == null ? JsonNodeFactory.instance.objectNode() : contextSnapshot;
    }
}
