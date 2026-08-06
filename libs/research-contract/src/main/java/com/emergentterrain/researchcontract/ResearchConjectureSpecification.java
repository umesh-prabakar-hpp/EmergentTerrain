package com.emergentterrain.researchcontract;

/**
 * Frozen, game-agnostic description of a conjecture selected for later investigation. This is
 * planning input only; it does not authorize verification, narration, publication, or effects.
 */
public record ResearchConjectureSpecification(String specificationVersion, String sourceId, String domain,
                                              String resultKind, String leftExpression, String rightExpression,
                                              String secondRightExpression, double primaryCoefficient,
                                              double secondaryCoefficient, double intercept,
                                              int confirmationCount, double maximumSlack,
                                              Long captureWindowStart, String selectionReason) {
    public static final String LEGACY_CONJECTURE_V1 = "legacy-conjecture-v1";
    public static final String QUEUE_CANDIDATE = "QUEUE_CANDIDATE";
    public static final String PROMOTED_ANOMALY = "PROMOTED_ANOMALY";

    public ResearchConjectureSpecification {
        requireText(specificationVersion, "specificationVersion");
        requireText(sourceId, "sourceId");
        requireText(domain, "domain");
        requireText(resultKind, "resultKind");
        requireText(leftExpression, "leftExpression");
        requireText(rightExpression, "rightExpression");
        if (!QUEUE_CANDIDATE.equals(selectionReason) && !PROMOTED_ANOMALY.equals(selectionReason)) {
            throw new IllegalArgumentException("selectionReason must be QUEUE_CANDIDATE or PROMOTED_ANOMALY");
        }
        if (!Double.isFinite(primaryCoefficient) || !Double.isFinite(secondaryCoefficient)
                || !Double.isFinite(intercept) || !Double.isFinite(maximumSlack)) {
            throw new IllegalArgumentException("conjecture numeric fields must be finite");
        }
        if (confirmationCount < 0 || captureWindowStart != null && captureWindowStart < 0) {
            throw new IllegalArgumentException("conjecture counts and windows must not be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
