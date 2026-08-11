package com.emergentterrain.researchcontract;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen, generic deterministic-computation input prepared after formulation and before a future
 * reviewed executor. It is an input record only: it does not authorize or report computation.
 */
public record ResearchComputationSpecification(String computationVersion, UUID subjectId, String domain, String topic,
                                               int parameterOne, double parameterTwo, double stepSize,
                                               double restartProbability, int candidateBreadth, double depthBias,
                                               double acceptanceThreshold) {
    public static final String LEGACY_COMPUTATION_INPUT_V1 = "legacy-computation-input-v1";

    public ResearchComputationSpecification {
        if (!LEGACY_COMPUTATION_INPUT_V1.equals(computationVersion)) {
            throw new IllegalArgumentException("Unsupported computation input version: " + computationVersion);
        }
        Objects.requireNonNull(subjectId, "subjectId");
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || parameterOne < 10 || parameterOne > 99
                || !Double.isFinite(parameterTwo) || parameterTwo < 1.0 || parameterTwo > 9.0
                || !unitInterval(stepSize) || !unitInterval(restartProbability)
                || candidateBreadth < 20 || candidateBreadth > 80
                || !unitInterval(depthBias) || !unitInterval(acceptanceThreshold)) {
            throw new IllegalArgumentException("A frozen computation input requires a bounded target, parameters, and scan controls");
        }
    }

    private static boolean unitInterval(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
