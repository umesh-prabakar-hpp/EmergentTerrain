package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Frozen, source-agnostic candidate selected before computation or narration begins. */
public record ResearchDraftPlan(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId,
                                String domain, String topic, String eligibility, String selectorVersion,
                                String inputFingerprint, String executionKind, JsonNode executionPayload,
                                ResearchParameterSample fallbackParameterSample,
                                ResearchFormulationSpecification formulationSpecification,
                                ResearchComputationSpecification computationSpecification, Instant plannedAt) {
    public ResearchDraftPlan {
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || eligibility == null || eligibility.isBlank() || selectorVersion == null || selectorVersion.isBlank()
                || inputFingerprint == null || inputFingerprint.isBlank() || executionKind == null || executionKind.isBlank()) {
            throw new IllegalArgumentException("domain, topic, eligibility, selectorVersion, inputFingerprint, and executionKind are required");
        }
        executionPayload = executionPayload == null ? JsonNodeFactory.instance.objectNode() : executionPayload;
        plannedAt = plannedAt == null ? Instant.now() : plannedAt;
    }

    /** Compatibility constructor for plans persisted before fallback-sampling parity was captured. */
    public ResearchDraftPlan(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId,
                             String domain, String topic, String eligibility, String selectorVersion,
                             String inputFingerprint, String executionKind, JsonNode executionPayload,
                             ResearchParameterSample fallbackParameterSample,
                             ResearchFormulationSpecification formulationSpecification, Instant plannedAt) {
        this(draftId, requestId, workspaceId, subjectId, domain, topic, eligibility, selectorVersion,
                inputFingerprint, executionKind, executionPayload, fallbackParameterSample, formulationSpecification,
                null, plannedAt);
    }

    /** Compatibility constructor for plans persisted before frozen formulation parity was captured. */
    public ResearchDraftPlan(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId,
                             String domain, String topic, String eligibility, String selectorVersion,
                             String inputFingerprint, String executionKind, JsonNode executionPayload,
                             ResearchParameterSample fallbackParameterSample, Instant plannedAt) {
        this(draftId, requestId, workspaceId, subjectId, domain, topic, eligibility, selectorVersion,
                inputFingerprint, executionKind, executionPayload, fallbackParameterSample, null, null, plannedAt);
    }

    /** Compatibility constructor for plans persisted before fallback-sampling parity was captured. */
    public ResearchDraftPlan(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId,
                             String domain, String topic, String eligibility, String selectorVersion,
                             String inputFingerprint, String executionKind, JsonNode executionPayload,
                             Instant plannedAt) {
        this(draftId, requestId, workspaceId, subjectId, domain, topic, eligibility, selectorVersion,
                inputFingerprint, executionKind, executionPayload, null, null, null, plannedAt);
    }
}
