package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Frozen, source-agnostic candidate selected before computation or narration begins. */
public record ResearchDraftPlan(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId,
                                String domain, String topic, String eligibility, String selectorVersion,
                                String inputFingerprint, Instant plannedAt) {
    public ResearchDraftPlan {
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || eligibility == null || eligibility.isBlank() || selectorVersion == null || selectorVersion.isBlank()
                || inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("domain, topic, eligibility, selectorVersion, and inputFingerprint are required");
        }
        plannedAt = plannedAt == null ? Instant.now() : plannedAt;
    }
}
