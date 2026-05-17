package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 F4 — unit tests for {@link DirectionOfFindingValidator}. The
 * canonical corpus example is P-a328-491: max deviation 0.694%, Hypothesis
 * bound 5%, Conclusion "rules out" the hypothesis. The validator must flag
 * because 0.694% is <em>within</em> 5%, not outside, so the polarity is
 * inverted.
 */
class DirectionOfFindingValidatorTest {

    @Test
    void p491SignatureFlags() {
        // Findings 0.694% deviation; Hypothesis bound 5%; Conclusion "ruled out".
        // 0.694 ≤ 5 → actuallyWithin = true. Conclusion uses outside-polarity
        // "ruled out". → flag.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "We expect convergence within 5% over the survey window.",
                "The maximum deviation was 0.694% across all evaluations.",
                "The hypothesis is therefore ruled out by the observed data.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("'outside'")
                .contains("0.694")
                .contains("5.0")
                .contains("actually within");
    }

    @Test
    void withinBoundAndConfirmedConclusionPasses() {
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Predicted to land within 5% across the regime.",
                "Maximum deviation 0.694% observed.",
                "Hypothesis confirmed by the close agreement.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void outsideBoundAndRefutedConclusionPasses() {
        // 12.4% > 5% → actuallyOutside; Conclusion refutes → matches.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Tolerance is within 5%.",
                "Max deviation 12.4% observed across the run.",
                "These data refute the hypothesis.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void outsideBoundButConfirmedConclusionFlagsInversely() {
        // 12.4% > 5% but Conclusion "confirmed" → wrong polarity.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Tolerance within 5%.",
                "Max deviation 12.4% observed.",
                "Hypothesis confirmed by the experiment.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("'within'").contains("actually outside");
    }

    @Test
    void missingHypothesisBoundReturnsEmpty() {
        // No "within X%" or "≤ X%" anywhere → can't decide → skip.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "We investigate the relationship across the survey window.",
                "Max deviation 0.694% observed.",
                "Hypothesis ruled out.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void missingFindingsDeviationReturnsEmpty() {
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Predicted within 5%.",
                "The result was consistent with the prediction across the run.",
                "Hypothesis confirmed.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void noPolarityWordReturnsEmpty() {
        // Conclusion uses interpretive prose without committing to a polarity.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Predicted within 5%.",
                "Max deviation 0.694%.",
                "The observed values indicate further investigation is needed.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void bothPolarityWordsPresentReturnsEmpty() {
        // Mixed signal — the validator declines to adjudicate.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Predicted within 5%.",
                "Max deviation 0.694%.",
                "Hypothesis confirmed in regime A but ruled out in regime B.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void nullInputsReturnEmpty() {
        assertThat(DirectionOfFindingValidator.validate(null, "x", "y")).isEmpty();
        assertThat(DirectionOfFindingValidator.validate("x", (String) null, "y")).isEmpty();
        assertThat(DirectionOfFindingValidator.validate("x", "y", null)).isEmpty();
    }

    @Test
    void alternateBoundSyntaxAtMostMatches() {
        // "at most 5%" should parse as a bound.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "The deviation should be at most 5%.",
                "Observed 12.4% deviation across the trials.",
                "Hypothesis confirmed.");
        assertThat(reasons).hasSize(1);
    }

    @Test
    void alternateFindingsSyntaxPostfixDeviationMatches() {
        // "0.694% deviation" matches FINDINGS_DEVIATION group 2.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Within 5%.",
                "We measured a 0.694% deviation against the closed form.",
                "Hypothesis ruled out.");
        assertThat(reasons).hasSize(1);
    }
}
