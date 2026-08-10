package com.emergentterrain.researchcontract;

/** Generic bounded distribution used to reproduce a legacy fallback research-parameter draw. */
public record ResearchParameterSamplingProfile(int parameterOneCenter, double parameterTwoCenter,
                                               int parameterOneRadius, double parameterTwoRadius) {
    public ResearchParameterSamplingProfile {
        if (parameterOneCenter < 10 || parameterOneCenter > 99
                || parameterTwoCenter < 1.0 || parameterTwoCenter > 9.0
                || parameterOneRadius < 0 || parameterTwoRadius < 0.0
                || !Double.isFinite(parameterTwoCenter) || !Double.isFinite(parameterTwoRadius)) {
            throw new IllegalArgumentException("Invalid bounded research parameter sampling profile");
        }
    }
}
