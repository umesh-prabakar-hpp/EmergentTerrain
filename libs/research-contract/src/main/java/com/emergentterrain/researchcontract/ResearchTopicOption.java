package com.emergentterrain.researchcontract;

import java.util.Objects;

/** One immutable topic option supplied to a generic draft selector. */
public record ResearchTopicOption(String topic, double weight) {
    public ResearchTopicOption {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic is required");
        if (!Double.isFinite(weight) || weight < 0.0) throw new IllegalArgumentException("weight must be nonnegative");
    }
}
