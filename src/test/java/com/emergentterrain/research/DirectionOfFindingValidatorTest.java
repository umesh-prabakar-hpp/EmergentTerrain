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

    // Phase 14 F4-ext (2026-05-21) — three-way contradiction overload.

    @Test
    void p883SignatureFlagsThreeWayContradiction() {
        // P-a328-883: Findings cites call_2 returning bestAngle=27.0°; Conclusion
        // stamps "null result"; trace has 1 successful call. → flag.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Range-optimal angle will be less than the 45° vacuum optimum.",
                java.util.Map.of(1,
                        "The optimal launch angle was verified as 27.0° from call_2, "
                                + "yielding a trajectory range of 34.66 m."),
                "The pre-registered prediction was not verified by direct computational "
                        + "evidence; this paper is recorded as a null result.",
                /* traceSize */ 1);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("null-result")
                .contains("size=1");
    }

    @Test
    void emptyTraceNullStampIsHonestNotContradictory() {
        // Trace empty + Conclusion null + Findings has no trace citation → no flag.
        // This is the honest path: no computation ran, no result claimed.
        List<String> reasons = DirectionOfFindingValidator.validate(
                "Range-optimal angle will be less than 45°.",
                java.util.Map.of(1,
                        "NULL_RESULT — no tool call executed; hypothesis untested."),
                "The hypothesis remains untested in this submission.",
                /* traceSize */ 0);
        assertThat(reasons).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void limitationVocabulary_isNotANullStamp_p802() {
        // 2026-07-06 (P-b72c-77627802): "an intermediate scale ... remains untested" is honest
        // scope disclosure in a POSITIVE conclusion (and the exact phrasing the phase-33 question
        // harvester rewards) — it must not read as a null verdict.
        String conclusion = "The scale-ladder sweep numerically confirms convergence to 2*gamma. "
                + "An intermediate scale (e.g., 10^7.5) remains untested; window-size sensitivity "
                + "was not verified across alternative sizes.";
        java.util.List<String> reasons = com.emergentterrain.research.DirectionOfFindingValidator
                .validate("mean_divisor_excess converges to 2*gamma",
                        java.util.Map.of(1, "[call_2] reported 1.15439 at 10^7."),
                        conclusion, 3);
        org.assertj.core.api.Assertions.assertThat(reasons)
                .noneMatch(r -> r.contains("stamps null-result"));
        // Real verdict stamps still fire: explicit phrase AND claim-subject negation.
        for (String nullStamp : new String[]{
                "The result is recorded as a null result.",
                "The pre-registered prediction was not verified.",
                "The hypothesis was not supported by the data.",
                "The conjecture remains untested."}) {
            org.assertj.core.api.Assertions.assertThat(
                    com.emergentterrain.research.DirectionOfFindingValidator.validate(
                            "h", java.util.Map.of(1, "[call_2] reported 1.154."), nullStamp, 3))
                    .as(nullStamp)
                    .anyMatch(r -> r.contains("stamps null-result"));
        }
    }
}
