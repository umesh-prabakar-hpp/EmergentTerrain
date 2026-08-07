package com.emergentterrain.researchcontract;

import java.util.List;

/** Immutable, ordered queue observation used to audit conjecture-selection parity. */
public record ResearchConjectureQueueSnapshot(String snapshotVersion, String domain,
                                              double ordinarySelectionProbability,
                                              List<ResearchConjectureSpecification> promotedCandidates,
                                              List<ResearchConjectureSpecification> ordinaryCandidates) {
    public static final String LEGACY_CONJECTURE_QUEUE_V1 = "legacy-conjecture-queue-v1";

    public ResearchConjectureQueueSnapshot {
        if (snapshotVersion == null || snapshotVersion.isBlank() || domain == null || domain.isBlank()
                || !Double.isFinite(ordinarySelectionProbability)
                || ordinarySelectionProbability < 0.0 || ordinarySelectionProbability > 1.0) {
            throw new IllegalArgumentException("Invalid frozen conjecture queue snapshot");
        }
        promotedCandidates = promotedCandidates == null ? List.of() : List.copyOf(promotedCandidates);
        ordinaryCandidates = ordinaryCandidates == null ? List.of() : List.copyOf(ordinaryCandidates);
    }
}
