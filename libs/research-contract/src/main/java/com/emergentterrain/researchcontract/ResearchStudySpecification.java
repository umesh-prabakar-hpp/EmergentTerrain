package com.emergentterrain.researchcontract;

/**
 * Frozen, game-agnostic definition of a bounded parameterized study. It describes a study only;
 * it does not authorize computation, narration, publication, or any game-side effect.
 */
public record ResearchStudySpecification(String specificationVersion, String studyId, String description,
                                         int rangeStart, int rangeEnd, String predicateExpression,
                                         String measurement, double baseline, String hypothesis,
                                         String expectedRelation, Double comparisonTolerance) {
    public static final String LEGACY_PARAMETERIZED_STUDY_V1 = "legacy-parameterized-study-v1";

    public ResearchStudySpecification {
        requireText(specificationVersion, "specificationVersion");
        requireText(studyId, "studyId");
        requireText(description, "description");
        requireText(predicateExpression, "predicateExpression");
        requireText(measurement, "measurement");
        requireText(expectedRelation, "expectedRelation");
        if (!Double.isFinite(baseline)) throw new IllegalArgumentException("baseline must be finite");
        if (comparisonTolerance != null
                && (!Double.isFinite(comparisonTolerance) || comparisonTolerance <= 0.0)) {
            throw new IllegalArgumentException("comparisonTolerance must be finite and positive when supplied");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
