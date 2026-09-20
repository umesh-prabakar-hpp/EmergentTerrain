package com.emergentterrain.researchcontract;

/** Display-safe evidence inventory for an immutable artifact bundle. */
public record ResearchEvidenceProvenanceSummary(
        boolean findingsRetained,
        boolean conclusionRetained,
        int recordedToolCallCount,
        ResearchLiteratureEvidenceSummary literature) { }
