package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

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
        validateCandidates(promotedCandidates, domain, ResearchConjectureSpecification.PROMOTED_ANOMALY);
        validateCandidates(ordinaryCandidates, domain, ResearchConjectureSpecification.QUEUE_CANDIDATE);
        validateUniqueSourceIds(promotedCandidates, ordinaryCandidates);
    }

    private static void validateCandidates(List<ResearchConjectureSpecification> candidates, String domain,
                                           String selectionReason) {
        if (candidates.stream().anyMatch(candidate -> candidate == null
                || !domain.equalsIgnoreCase(candidate.domain())
                || !selectionReason.equals(candidate.selectionReason()))) {
            throw new IllegalArgumentException("Frozen conjecture queue candidates must match snapshot domain and priority");
        }
    }

    private static void validateUniqueSourceIds(List<ResearchConjectureSpecification> promoted,
                                                List<ResearchConjectureSpecification> ordinary) {
        Set<String> sourceIds = new HashSet<>();
        if (java.util.stream.Stream.concat(promoted.stream(), ordinary.stream())
                .anyMatch(candidate -> !sourceIds.add(candidate.sourceId()))) {
            throw new IllegalArgumentException("Frozen conjecture queue candidates must have unique source ids");
        }
    }
}
