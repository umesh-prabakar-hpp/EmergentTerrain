package com.emergentterrain.researchcontract;

import java.util.Objects;
import java.util.UUID;

/** A frozen fallback sample, not a computation request or result. */
public record ResearchParameterSample(String sampleVersion, UUID subjectId, String domain, String topic,
                                      int parameterOne, double parameterTwo) {
    public static final String LEGACY_FALLBACK_SAMPLING_V1 = "legacy-fallback-sampling-v1";

    public ResearchParameterSample {
        if (!LEGACY_FALLBACK_SAMPLING_V1.equals(sampleVersion)) {
            throw new IllegalArgumentException("Unsupported parameter sample version: " + sampleVersion);
        }
        Objects.requireNonNull(subjectId, "subjectId");
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || parameterOne < 10 || parameterOne > 99
                || !Double.isFinite(parameterTwo) || parameterTwo < 1.0 || parameterTwo > 9.0) {
            throw new IllegalArgumentException("Invalid frozen fallback parameter sample");
        }
    }
}
