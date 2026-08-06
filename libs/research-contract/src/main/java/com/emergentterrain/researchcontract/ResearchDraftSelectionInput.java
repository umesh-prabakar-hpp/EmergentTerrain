package com.emergentterrain.researchcontract;

import java.util.List;

/** Frozen generic inputs required to select one standard research draft deterministically. */
public record ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                          List<ResearchDraftCandidate> candidates,
                                          ResearchDraftExecutionIntent executionIntent) {
    public ResearchDraftSelectionInput {
        if (selectorVersion == null || selectorVersion.isBlank()) {
            throw new IllegalArgumentException("selectorVersion is required");
        }
        if (!"WEIGHTED".equals(topicSelectionMode) && !"UNIFORM".equals(topicSelectionMode)) {
            throw new IllegalArgumentException("topicSelectionMode must be WEIGHTED or UNIFORM");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        executionIntent = executionIntent == null ? ResearchDraftExecutionIntent.standardCatalog() : executionIntent;
    }

    public ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                       List<ResearchDraftCandidate> candidates) {
        this(selectorVersion, selectionSeed, topicSelectionMode, candidates,
                ResearchDraftExecutionIntent.standardCatalog());
    }
}
