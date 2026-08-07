package com.emergentterrain.researchcontract;

/** Frozen settings for deterministic history-aware topic reselection. */
public record ResearchTopicReselectionPolicy(String policyVersion, boolean enabled, boolean yieldAware,
                                             int maxRetries, double lowYieldPeerCeiling,
                                             double lowYieldNullDecay) {
    public static final String LEGACY_TOPIC_BACKOFF_V1 = "legacy-topic-backoff-v1";

    public ResearchTopicReselectionPolicy {
        if (policyVersion == null || policyVersion.isBlank() || maxRetries < 0
                || !Double.isFinite(lowYieldPeerCeiling) || !Double.isFinite(lowYieldNullDecay)
                || lowYieldNullDecay < 0.0 || lowYieldNullDecay > 1.0) {
            throw new IllegalArgumentException("Invalid topic reselection policy");
        }
    }

    public static ResearchTopicReselectionPolicy disabled() {
        return new ResearchTopicReselectionPolicy(LEGACY_TOPIC_BACKOFF_V1, false, false, 0, 0.0, 1.0);
    }
}
