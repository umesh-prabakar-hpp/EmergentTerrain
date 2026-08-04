package com.emergentterrain.research.publication;

import java.util.List;

/** Immutable result of the publication-quality gate. */
public record PublicationAssessment(
        PublicationLabel label,
        List<String> missingRequirements
) {
    public boolean isArtifactBackedCandidate() {
        return label == PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE;
    }
}
