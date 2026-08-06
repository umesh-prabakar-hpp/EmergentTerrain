package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;

import java.util.UUID;

final class FrozenDraftSelectionFixtures {
    private FrozenDraftSelectionFixtures() { }

    static ResearchDraftSelectionInput primaryInput(UUID subjectId) {
        return new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 11L, "WEIGHTED",
                java.util.List.of(new com.emergentterrain.researchcontract.ResearchDraftCandidate(subjectId,
                        "PRIMARY", 0.8, "MATHEMATICS", java.util.List.of(
                        new com.emergentterrain.researchcontract.ResearchTopicOption("zero-weight", 0.0),
                        new com.emergentterrain.researchcontract.ResearchTopicOption("selected-topic", 1.0)))));
    }
}
