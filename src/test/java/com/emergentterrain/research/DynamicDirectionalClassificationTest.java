package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicDirectionalClassificationTest {

    @Test
    void aboveBaselineStudySupportsTheObservedAbundantOrPerfectCensus() {
        MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(problem(
                "isAbundant OR isPerfect", 61, 411, 0.17,
                DynamicProblem.ExpectedRelation.ABOVE_BASELINE,
                "The exact finite-window fraction exceeds the pre-registered baseline of 0.17."));

        assertThat(result.secondaryValue()).isEqualTo(86.0 / 350.0);
        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(UniversityService.classifyResult(result, 0.50)).isEqualTo(ResultClassification.SUPPORTED);
    }

    @Test
    void belowBaselineStudySupportsTheSameObservedPrimeFractionAgainstAHigherBaseline() {
        MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(problem(
                "isPrime", 1, 200, 0.80, DynamicProblem.ExpectedRelation.BELOW_BASELINE,
                "The prime fraction is below the pre-registered baseline."));

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
    }

    @Test
    void approximateEqualityStudyUsesItsLockedTolerance() {
        MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(problem(
                "isPrime", 1, 200, 0.80, DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL,
                "The prime fraction is approximately equal to the pre-registered baseline.", 0.05));

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        assertThat(UniversityService.classifyResult(result, 0.50)).isEqualTo(ResultClassification.NULL_RESULT);
    }

    @Test
    void approximateEqualityOutcomeChangesOnlyAtTheLockedToleranceBoundary() {
        MathPhysicsEngine.ResearchResult withinLockedBand = DynamicComputeEngine.compute(problem(
                "isAbundant", 420, 500, 0.24, DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL,
                "The finite-window abundant-number density is within the pre-registered absolute band.", 0.015));
        MathPhysicsEngine.ResearchResult outsideLockedBand = DynamicComputeEngine.compute(problem(
                "isAbundant", 420, 500, 0.24, DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL,
                "The finite-window abundant-number density is within the pre-registered absolute band.", 0.005));

        assertThat(withinLockedBand.secondaryValue()).isEqualTo(0.25);
        assertThat(withinLockedBand.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(outsideLockedBand.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
    }

    @Test
    void legacyDirectiveInfersAnAboveBaselineRelationFromItsHypothesis() {
        DynamicProblem legacy = new DynamicProblem("legacy", "legacy", 61, 411,
                "isAbundant OR isPerfect", "fraction_satisfying", 0.17,
                "The exact finite-window fraction exceeds the pre-registered baseline of 0.17.");

        assertThat(legacy.expectedRelation()).isEqualTo(DynamicProblem.ExpectedRelation.ABOVE_BASELINE);
        assertThat(DynamicComputeEngine.compute(legacy).claimStatus())
                .isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
    }

    private static DynamicProblem problem(String predicate, int start, int end, double baseline,
                                          DynamicProblem.ExpectedRelation relation, String hypothesis) {
        return new DynamicProblem("direction", "direction", start, end, predicate,
                "fraction_satisfying", baseline, hypothesis, relation);
    }

    private static DynamicProblem problem(String predicate, int start, int end, double baseline,
                                          DynamicProblem.ExpectedRelation relation, String hypothesis,
                                          double tolerance) {
        return new DynamicProblem("direction", "direction", start, end, predicate,
                "fraction_satisfying", baseline, hypothesis, relation, tolerance);
    }
}
