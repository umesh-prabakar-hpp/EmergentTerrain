package com.emergentterrain.researchcontract;

/** Deterministic selection observation; a null specification means catalog fallback. */
public record ResearchConjectureQueueSelection(ResearchConjectureSpecification specification,
                                               int preTopicRandomDoubleDraws) {
    public ResearchConjectureQueueSelection {
        if (preTopicRandomDoubleDraws < 0 || preTopicRandomDoubleDraws > 1) {
            throw new IllegalArgumentException("preTopicRandomDoubleDraws must be 0 or 1");
        }
    }
}
