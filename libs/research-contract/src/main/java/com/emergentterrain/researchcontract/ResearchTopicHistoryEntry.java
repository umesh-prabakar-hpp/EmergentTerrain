package com.emergentterrain.researchcontract;

/** One immutable prior outcome used by a future topic-selection replay. */
public record ResearchTopicHistoryEntry(String domain, String topic, String outcome, double peerScore) {
    public ResearchTopicHistoryEntry {
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || outcome == null || outcome.isBlank() || !Double.isFinite(peerScore)) {
            throw new IllegalArgumentException("domain, topic, outcome, and a finite peerScore are required");
        }
    }
}
