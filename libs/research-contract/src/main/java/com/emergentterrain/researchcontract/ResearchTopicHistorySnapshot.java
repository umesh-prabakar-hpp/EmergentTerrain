package com.emergentterrain.researchcontract;

import java.util.List;

/** Frozen generic history input for a future deterministic topic-reselection stage. */
public record ResearchTopicHistorySnapshot(String snapshotVersion, List<ResearchTopicHistoryEntry> entries) {
    public static final String LEGACY_TOPIC_HISTORY_V1 = "legacy-topic-history-v1";

    public ResearchTopicHistorySnapshot {
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("snapshotVersion is required");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static ResearchTopicHistorySnapshot empty() {
        return new ResearchTopicHistorySnapshot(LEGACY_TOPIC_HISTORY_V1, List.of());
    }
}
