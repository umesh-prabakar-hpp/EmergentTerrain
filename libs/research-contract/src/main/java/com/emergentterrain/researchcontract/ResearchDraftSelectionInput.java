package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Frozen generic inputs required to select one standard research draft deterministically. */
public record ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                          List<ResearchDraftCandidate> candidates,
                                          ResearchDraftExecutionIntent executionIntent,
                                          ResearchTopicHistorySnapshot topicHistory,
                                          ResearchTopicReselectionPolicy topicReselectionPolicy,
                                          int preTopicRandomDoubleDraws) {
    public static final String LEGACY_STANDARD_CATALOG_V1 = "legacy-standard-catalog-v1";

    public ResearchDraftSelectionInput {
        if (selectorVersion == null || selectorVersion.isBlank()) {
            throw new IllegalArgumentException("selectorVersion is required");
        }
        if (!"WEIGHTED".equals(topicSelectionMode) && !"UNIFORM".equals(topicSelectionMode)) {
            throw new IllegalArgumentException("topicSelectionMode must be WEIGHTED or UNIFORM");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        Set<java.util.UUID> candidateIds = new HashSet<>();
        for (ResearchDraftCandidate candidate : candidates) {
            if (!candidateIds.add(candidate.subjectId())) {
                throw new IllegalArgumentException("candidates must not contain duplicate subject IDs");
            }
        }
        executionIntent = executionIntent == null ? ResearchDraftExecutionIntent.standardCatalog() : executionIntent;
        topicHistory = topicHistory == null ? ResearchTopicHistorySnapshot.empty() : topicHistory;
        topicReselectionPolicy = topicReselectionPolicy == null ? ResearchTopicReselectionPolicy.disabled()
                : topicReselectionPolicy;
        if (preTopicRandomDoubleDraws < 0 || preTopicRandomDoubleDraws > 1) {
            throw new IllegalArgumentException("preTopicRandomDoubleDraws must be 0 or 1");
        }
    }

    public ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                       List<ResearchDraftCandidate> candidates) {
        this(selectorVersion, selectionSeed, topicSelectionMode, candidates,
                ResearchDraftExecutionIntent.standardCatalog(), ResearchTopicHistorySnapshot.empty(),
                ResearchTopicReselectionPolicy.disabled(), 0);
    }

    public ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                       List<ResearchDraftCandidate> candidates,
                                       ResearchDraftExecutionIntent executionIntent) {
        this(selectorVersion, selectionSeed, topicSelectionMode, candidates, executionIntent,
                ResearchTopicHistorySnapshot.empty(), ResearchTopicReselectionPolicy.disabled());
    }

    public ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                       List<ResearchDraftCandidate> candidates,
                                       ResearchDraftExecutionIntent executionIntent,
                                       ResearchTopicHistorySnapshot topicHistory) {
        this(selectorVersion, selectionSeed, topicSelectionMode, candidates, executionIntent, topicHistory,
                ResearchTopicReselectionPolicy.disabled(), 0);
    }

    public ResearchDraftSelectionInput(String selectorVersion, long selectionSeed, String topicSelectionMode,
                                       List<ResearchDraftCandidate> candidates,
                                       ResearchDraftExecutionIntent executionIntent,
                                       ResearchTopicHistorySnapshot topicHistory,
                                       ResearchTopicReselectionPolicy topicReselectionPolicy) {
        this(selectorVersion, selectionSeed, topicSelectionMode, candidates, executionIntent, topicHistory,
                topicReselectionPolicy, 0);
    }
}
