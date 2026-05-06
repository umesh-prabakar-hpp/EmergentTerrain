package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for UniversityService.classifyResult — the deterministic gate that assigns
 * SUPPORTED / PARTIALLY_SUPPORTED / NULL_RESULT / DEGENERATE before any LLM call.
 *
 * All inputs are built from MathPhysicsEngine.ResearchResult constructors; no
 * real computation is run so tests are fast and deterministic.
 */
class ResultClassificationTest {

    // ---- DEGENERATE cases ----

    @Test
    void degenerateWhenReviewValueIsNaN() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0,
                Double.NaN, MathPhysicsEngine.ClaimStatus.SUPPORTED, "ok");
        assertThat(UniversityService.classifyResult(result, 0.5))
                .isEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void degenerateWhenReviewValueIsPositiveInfinity() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0,
                Double.POSITIVE_INFINITY, MathPhysicsEngine.ClaimStatus.SUPPORTED, "ok");
        assertThat(UniversityService.classifyResult(result, 0.5))
                .isEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void degenerateWhenAllZeroWithoutTheorySupport() {
        // primaryValue=0, secondaryValue=0, theoryConsistent=false → sentinel / engine fallback
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 0.0, 0.0, 0.0, false, "zero sentinel");
        assertThat(UniversityService.classifyResult(result, 0.5))
                .isEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void notDegenerateWhenAllZeroButTheoryConsistent() {
        // Zero-value outputs are allowed if the engine explicitly marks theory as consistent
        // (4-arg constructor infers theoryConsistent=true when all values are finite)
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 0.0, 0.0, 0.0, true, "valid zero state");
        assertThat(UniversityService.classifyResult(result, 0.5))
                .isNotEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void degenerateWhenAuthorScoreIsExactlyZero() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, true, "supported");
        assertThat(UniversityService.classifyResult(result, 0.0))
                .isEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void degenerateWhenAuthorScoreIsNegative() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, true, "supported");
        assertThat(UniversityService.classifyResult(result, -0.01))
                .isEqualTo(ResultClassification.DEGENERATE);
    }

    // ---- NULL_RESULT cases ----

    @Test
    void nullResultWhenAuthorScoreBelowFloor() {
        // Any claimStatus with authorScore < 0.05 → NULL_RESULT
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, true, "supported");
        assertThat(UniversityService.classifyResult(result, 0.04))
                .isEqualTo(ResultClassification.NULL_RESULT);
    }

    @Test
    void nullResultWhenClaimRefuted() {
        // theoryConsistent=false → claimStatus=REFUTED via the (findings, p1,p2,p3, bool, check) constructor
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, false, "theory refuted");
        assertThat(UniversityService.classifyResult(result, 0.4))
                .isEqualTo(ResultClassification.NULL_RESULT);
    }

    @Test
    void nullResultWhenObservationalWithVeryLowScore() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, 1.0,
                MathPhysicsEngine.ClaimStatus.OBSERVATIONAL, "observational");
        // OBSERVATIONAL + score < 0.12 → NULL_RESULT
        assertThat(UniversityService.classifyResult(result, 0.10))
                .isEqualTo(ResultClassification.NULL_RESULT);
    }

    @Test
    void nullResultWhenAuthorScoreExactlyAtFloorBoundary() {
        // Score = 0.049 (just below the 0.05 floor) → NULL_RESULT
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, true, "supported");
        assertThat(UniversityService.classifyResult(result, 0.049))
                .isEqualTo(ResultClassification.NULL_RESULT);
    }

    // ---- SUPPORTED cases ----

    @Test
    void supportedWhenClaimStatusIsSupported() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, true, "theory check passed");
        assertThat(UniversityService.classifyResult(result, 0.6))
                .isEqualTo(ResultClassification.SUPPORTED);
    }

    @Test
    void supportedWhenClaimExplicitlySupported() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 2.0, 1.5, 1.0, 2.0,
                MathPhysicsEngine.ClaimStatus.SUPPORTED, "confirmed");
        assertThat(UniversityService.classifyResult(result, 0.8))
                .isEqualTo(ResultClassification.SUPPORTED);
    }

    // ---- PARTIALLY_SUPPORTED cases ----

    @Test
    void partiallySupportedWhenClaimStatusIsPartial() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, 1.0,
                MathPhysicsEngine.ClaimStatus.PARTIAL, "partial evidence");
        assertThat(UniversityService.classifyResult(result, 0.3))
                .isEqualTo(ResultClassification.PARTIALLY_SUPPORTED);
    }

    @Test
    void partiallySupportedWhenObservationalAboveScoreThreshold() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, 1.0,
                MathPhysicsEngine.ClaimStatus.OBSERVATIONAL, "observational");
        // OBSERVATIONAL + score >= 0.12 → PARTIALLY_SUPPORTED
        assertThat(UniversityService.classifyResult(result, 0.15))
                .isEqualTo(ResultClassification.PARTIALLY_SUPPORTED);
    }

    @Test
    void observationalBoundaryAt0_12IsPartiallySupported() {
        var result = new MathPhysicsEngine.ResearchResult(
                "findings", 1.0, 1.0, 1.0, 1.0,
                MathPhysicsEngine.ClaimStatus.OBSERVATIONAL, "observational");
        assertThat(UniversityService.classifyResult(result, 0.12))
                .isEqualTo(ResultClassification.PARTIALLY_SUPPORTED);
    }

    // ---- Real computation round-trip ----

    @Test
    void realComputeDefaultPhysicsResultIsNotDegenerate() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("PHYSICS", 0, 50, 3.0);
        // A real computation with mid-range params should produce a finite, non-zero result
        assertThat(UniversityService.classifyResult(result, 0.2))
                .isNotEqualTo(ResultClassification.DEGENERATE);
    }

    @Test
    void classifyResultWithRealMathematicsComputation() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 1, 40, 2.0);
        double score = MathPhysicsEngine.scoreResult("MATHEMATICS", 1, result.reviewValue(),
                MathPhysicsEngine.SearchParams.neutral());
        ResultClassification cls = UniversityService.classifyResult(result, score);
        // Classification must be one of the four valid values, never null
        assertThat(cls).isNotNull()
                .isIn((Object[]) ResultClassification.values());
    }
}
