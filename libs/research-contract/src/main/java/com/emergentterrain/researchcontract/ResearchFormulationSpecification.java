package com.emergentterrain.researchcontract;

import java.util.Objects;
import java.util.UUID;

/** Frozen, display-safe formulation output prepared before any computation. */
public record ResearchFormulationSpecification(String formulationVersion, UUID subjectId, String domain, String topic,
                                               int parameterOne, double parameterTwo, String researchQuestion,
                                               String hypothesis, String prediction, String falsificationCondition,
                                               String mappingNotes) {
    public static final String LEGACY_FORMULATION_V1 = "legacy-formulation-v1";

    public ResearchFormulationSpecification {
        if (!LEGACY_FORMULATION_V1.equals(formulationVersion)) {
            throw new IllegalArgumentException("Unsupported formulation version: " + formulationVersion);
        }
        Objects.requireNonNull(subjectId, "subjectId");
        if (domain == null || domain.isBlank() || topic == null || topic.isBlank()
                || parameterOne < 10 || parameterOne > 99
                || !Double.isFinite(parameterTwo) || parameterTwo < 1.0 || parameterTwo > 9.0
                || researchQuestion == null || researchQuestion.isBlank() || hypothesis == null || hypothesis.isBlank()
                || prediction == null || prediction.isBlank()) {
            throw new IllegalArgumentException("A frozen formulation requires target, bounded parameters, question, hypothesis, and prediction");
        }
        researchQuestion = bounded(researchQuestion, "researchQuestion");
        hypothesis = bounded(hypothesis, "hypothesis");
        prediction = bounded(prediction, "prediction");
        falsificationCondition = optionalBounded(falsificationCondition, "falsificationCondition");
        mappingNotes = optionalBounded(mappingNotes, "mappingNotes");
    }

    private static String bounded(String value, String field) {
        if (value.length() > 4096) throw new IllegalArgumentException(field + " exceeds 4096 characters");
        return value;
    }

    private static String optionalBounded(String value, String field) {
        return value == null ? "" : bounded(value, field);
    }
}
