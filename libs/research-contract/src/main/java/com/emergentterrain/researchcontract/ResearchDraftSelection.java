package com.emergentterrain.researchcontract;

import java.util.Objects;
import java.util.UUID;

/** Deterministic output of a frozen generic standard-draft selection. */
public record ResearchDraftSelection(UUID subjectId, String eligibility, String domain, String topic) {
    public ResearchDraftSelection {
        Objects.requireNonNull(subjectId, "subjectId");
        if (eligibility == null || eligibility.isBlank() || domain == null || domain.isBlank()
                || topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("eligibility, domain, and topic are required");
        }
    }
}
