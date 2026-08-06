package com.emergentterrain.researchcontract;

/**
 * Frozen, game-agnostic parameter override for a subsequent study. This is planning input only
 * until a generic computation stage is extracted.
 */
public record ResearchParameterOverride(String overrideVersion, int parameterOne, double parameterTwo,
                                        Double predictedScore, String priorMethodology) {
    public static final String LEGACY_FOLLOW_UP_V1 = "legacy-follow-up-v1";

    public ResearchParameterOverride {
        if (overrideVersion == null || overrideVersion.isBlank()) {
            throw new IllegalArgumentException("overrideVersion is required");
        }
        if (!Double.isFinite(parameterTwo)) throw new IllegalArgumentException("parameterTwo must be finite");
        if (predictedScore != null && !Double.isFinite(predictedScore)) {
            throw new IllegalArgumentException("predictedScore must be finite when supplied");
        }
    }
}
