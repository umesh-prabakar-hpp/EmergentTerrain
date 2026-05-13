package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 B1 — each test is anchored to a specific paper ID in
 * {@code REVIEW_REPORT.md} so the corpus failure mode the validator targets
 * stays legible.
 */
class NumericalConsistencyValidatorTest {

    // -------- Check A: "close to N" with no nearby actual --------

    @Test
    void pA328_14_closeToTwoTimesWithSubTwoMultipliersFlagged() {
        // P-a328-14: observed multipliers 1.07× and 1.11×; conclusion claims
        // they are "close to the expected 2× value". 1.07/2 = 0.46 — far outside ±20%.
        String hypothesis = "Van't Hoff +10K rule predicts a multiplier of 2×.";
        String findings   = "Observed multipliers: Ea=10kJ→1.07× Ea=20kJ→1.11×.";
        String conclusion = "Observed values 1.07× and 1.11× are close to the expected 2× value.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("close to 2");
    }

    @Test
    void closeToTwoTimesWithMultiplierNearTwoPassesUnflagged() {
        // 1.85× is within ±20% of 2×. Should pass.
        String hypothesis = "Doubling expected.";
        String findings   = "Ratios: 1.85× at Ea=100 kJ.";
        String conclusion = "The ratio of 1.85× is close to the expected 2× value.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isEmpty();
    }

    @Test
    void approximatelyAnchorAlsoCaughtWithoutNearbyObserved() {
        String hypothesis = "Expected critical resistance 50 Ω.";
        String findings   = "Computed values: 5 Ω, 7 Ω, 12 Ω.";
        String conclusion = "R_crit is approximately 50 Ω based on the sweep.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("'close to 50");
    }

    // -------- Check B: Hypothesis "near X" vs Conclusion "starting Y" --------

    @Test
    void pA328_50_hypothesisNear380ConclusionStarting710Flagged() {
        // P-a328-50: hypothesis "near 380"; conclusion "starting 710". 710/380 ≈ 1.87 — 87% off.
        String hypothesis = "Prime-gap defect spacing near 380 µm will produce thermal stress.";
        String findings   = "Computed defects show varying spacing.";
        String conclusion = "Critical loading observed starting 710 µm.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.stream().anyMatch(r -> r.contains("710"))).isTrue();
    }

    @Test
    void pA328_61_hypothesisNear100ConclusionStarting348Flagged() {
        // P-a328-61: hypothesis "near 100"; conclusion "starting 348". 348/100 = 3.48 — 248% off.
        String hypothesis = "Window starting near 100 µm.";
        String findings   = "Survey across multiple ranges.";
        String conclusion = "Effect observed starting 348 µm.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.stream().anyMatch(r -> r.contains("348"))).isTrue();
    }

    @Test
    void hypothesisNear500ConclusionAt600PassesUnflagged() {
        // 600/500 = 1.2 — within ±50% band. Should pass.
        String hypothesis = "Crossover expected near 500.";
        String findings   = "Survey scanned.";
        String conclusion = "Refined estimate places it at 600.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isEmpty();
    }

    @Test
    void multipleHypothesisAnchorsAnyCompatibleSatisfies() {
        // Hypothesis cites two anchors; conclusion lands near the second. Should pass.
        String hypothesis = "Phenomenon expected near 100 or near 700 across two regimes.";
        String findings   = "Both regimes scanned.";
        String conclusion = "Strong signal found at 720.";  // within ±50% of 700

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isEmpty();
    }

    // -------- Tolerance and null safety --------

    @Test
    void nullSectionsReturnEmptyReasonList() {
        assertThat(NumericalConsistencyValidator.validate(null, "f", "c")).isEmpty();
        assertThat(NumericalConsistencyValidator.validate("h", null, "c")).isEmpty();
        assertThat(NumericalConsistencyValidator.validate("h", "f", null)).isEmpty();
    }

    @Test
    void cleanPaperReturnsEmptyReasons() {
        String hypothesis = "Pendulum period error exceeds 1% above θ_c < 20°.";
        String findings   = "θ=5°→0.05% θ=10°→0.19% θ=15°→0.43% θ=20°→0.76% θ=25°→1.19%.";
        String conclusion = "Threshold observed at θ ≈ 25°. The 1% threshold exceeds 1.19% precisely.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).isEmpty();
    }
}
