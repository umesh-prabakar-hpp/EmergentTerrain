package com.emergentterrain.researchcontract;

/** Read-only presence indicators for the locked study inputs of an artifact bundle. */
public record ResearchStudyProvenanceSummary(
        boolean preregistrationAvailable,
        boolean hypothesisDeclared,
        boolean predictionDeclared,
        boolean falsificationConditionDeclared,
        boolean computationalStudyPlanDeclared,
        boolean protocolReuseRecorded) { }
