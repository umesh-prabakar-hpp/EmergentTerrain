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
        assertThat(NumericalConsistencyValidator.validate("h", (String) null, "c")).isEmpty();
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

    // ---- Phase 7 F5.5: Check C — unsourced salient numbers vs tool-call trace ----

    @Test
    void f5_5_traceAwareOverloadFlagsSalientNumberNotInAnySource() {
        // Findings cites "Q = 42.30" — a precise value not in the hypothesis, not
        // in the conclusion, and not in any tool result. The new check fires.
        String hypothesis = "Underdamped RLC resonance near 3500 Hz.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "The sweep reported Q = 42.30 at the resonance peak.");
        String conclusion = "Q approaches its theoretical maximum.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{\"param1\":\"L\"}",
                        "{\"peak_q\":18.75,\"argmax_L\":0.0021}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons)
                .anyMatch(r -> r.contains("42.30") && r.contains("not within ±1%"));
    }

    @Test
    void f5_5_traceAwareOverloadPassesWhenSalientNumberMatchesToolResult() {
        // Same as above but the trace contains 42.30 — the Findings number is
        // legitimately sourced, no flag.
        String hypothesis = "Underdamped RLC resonance near 3500 Hz.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "The sweep reported Q = 42.30 at the resonance peak.");
        String conclusion = "Q approaches its theoretical maximum.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{\"param1\":\"L\"}",
                        "{\"peak_q\":42.30,\"argmax_L\":0.0021}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("42.30"));
    }

    @Test
    void f5_5_traceAwareOverloadPassesWithinOnePercentTolerance() {
        // Findings cites 1229; trace returned 1230. Within ±1% — pass.
        String hypothesis = "Prime density expected near 10000.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "The predicate scan found density = 0.1229 over the range.");
        String conclusion = "Density consistent with prediction.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{\"n_max\":10000}",
                        "{\"hits\":1230,\"density\":0.1230}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("0.1229"));
    }

    @Test
    void f5_5_traceAwareOverloadIgnoresPlainIntegersAsNonSalient() {
        // "2 of 3 reviewers" / "first 5 iterations" must not fire even though those
        // integers aren't anywhere in the trace. Plain integers are not "salient".
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "Across 5 trials and 10 sweeps the result held.");
        String conclusion = "Conclusion text.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{\"peak_q\":42.30}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void f5_5_traceAwareOverloadAllowsScientificNotation() {
        // 1.23e-3 in findings, 1.23e-3 in trace — pass.
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "Inductance settled at L = 1.23e-3 H.");
        String conclusion = "Below textbook threshold.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{\"param1\":\"L\"}",
                        "{\"argmax_L\":1.23e-3,\"peak_q\":18.75}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("1.23"));
    }

    @Test
    void f5_5_nullOrEmptyTraceShortCircuitsCheckC() {
        // No trace → Check C is skipped. Other checks still run, but if hypothesis /
        // conclusion are clean, the result is empty.
        String hypothesis = "Resonance near 3500 Hz.";
        java.util.Map<Integer, String> findings =
                java.util.Map.of(1, "Observed peak at Q = 42.30 — unsourced if Check C ran.");
        String conclusion = "Consistent with theory.";

        assertThat(NumericalConsistencyValidator.validate(hypothesis, findings, conclusion, null))
                .noneMatch(r -> r.contains("42.30"));
        assertThat(NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, java.util.List.of()))
                .noneMatch(r -> r.contains("42.30"));
    }

    @Test
    void f5_5_traceAwareOverloadFlagsMultipleUnsourcedNumbers() {
        // Two unsourced values appear — both should be flagged. Hypothesis range
        // and trace result are deliberately kept far enough apart from the two
        // findings values that neither falls inside the ±1% match band.
        String hypothesis = "Resonance near 100.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Q = 42.30 and peak frequency f₀ = 3789.42 Hz (neither in the trace).");
        String conclusion = "Consistent with prediction.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{\"peak_q\":18.75}", 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons)
                .anyMatch(r -> r.contains("42.30"))
                .anyMatch(r -> r.contains("3789.42"));
    }
}
