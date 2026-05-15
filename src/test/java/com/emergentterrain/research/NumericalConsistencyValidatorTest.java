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

    // ---- Phase 12 D2b: errored-call detection (P-a328-428) ----

    @Test
    void d2b_findingsCitingErroredCallFlagged() {
        // P-a328-428 signature: prose cites [call_1] for the headline value but
        // call_1's resultJson carries an error / safety-rejection marker.
        String hypothesis = "Drag-influenced optimal angle expected near 26°.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Optimal angle 26° with range 60.3 m [call_1] was the best result.");
        String conclusion = "Range-maximising angle confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "{\"rejected\": true, \"reason\":\"REJECTED by safety review: array literal\"}",
                        0, "call_1"),
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "{\"angle\": 30, \"range\": 158.79}", 1, "call_2"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("call_1")
                && (r.contains("errored") || r.contains("rejected")));
    }

    @Test
    void d2b_findingsCitingUnknownCallIdFlagged() {
        // Prose cites [call_9] but trace only has 2 calls.
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The peak was 42.50 [call_9] across the sweep.");
        String conclusion = "Consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}", "{\"peak\": 42.50}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("call_9")
                && r.contains("does not appear"));
    }

    @Test
    void d2b_findingsCitingSuccessfulCallNotFlagged() {
        // call_1 has a normal result — no error markers. Don't flag.
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Peak Q = 42.30 [call_1] at resonance.");
        String conclusion = "Consistent with theory.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{\"peak_q\": 42.30}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("call_1") && r.contains("errored"));
    }

    // ---- Phase 12 D2c: empty-trace + salient findings (P-a328-419) ----

    @Test
    void d2c_emptyTraceWithSalientFindingsFlagged() {
        // P-a328-419 signature: prof stage errored before any tool call landed,
        // so profTrace is an empty list (not null). Findings still cite values.
        String hypothesis = "Composite failure mode varies with V_f.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Failure observed: σ = 200 MPa, 640 MPa, 900 MPa across the V_f sweep.");
        String conclusion = "Failure mode transitions confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of();

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("Trace is empty")
                && r.contains("no Methods anchor"));
    }

    @Test
    void d2c_nullTraceDoesNotFlag() {
        // Distinguish null trace (professor stage never attempted; JSON-path /
        // Ollama-fallback / legacy callers) from empty trace (D2c case).
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Findings cite 42.30 with no source.");
        String conclusion = "Consistent.";

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, null);
        assertThat(reasons).noneMatch(r -> r.contains("Trace is empty"));
    }

    @Test
    void d2c_emptyTraceWithNoSalientFindingsDoesNotFlag() {
        // Trace empty but findings has only plain integers / counts — no
        // salient values to anchor. Don't flag.
        String hypothesis = "Hypothesis text.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Across 5 trials over 10 iterations the result held.");
        String conclusion = "Consistent.";

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, java.util.List.of());
        assertThat(reasons).noneMatch(r -> r.contains("Trace is empty"));
    }

    // ---- Phase 12 D2d: unit-anchored integers (P-a328-417) ----

    @Test
    void d2d_unitAnchoredIntegerNotInTraceFlagged() {
        // P-a328-417 signature: prose σ_y,max = 2377 MPa derived from prose-stated
        // σ₀, k, d but the trace's actual σ_y,max was 149 MPa.
        String hypothesis = "Hall-Petch yield strength scales with grain size.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Maximum yield stress of approximately 2,377 MPa [call_1] at grain size 0.050 µm.");
        String conclusion = "Hall-Petch relationship confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}",
                        "Hall-Petch sweep (σ_0=60 MPa, k=405 MPa·µm^½): max σ_y=149 MPa at d=20.75 µm.",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("2377 MPa")
                && r.contains("derived-formula divergence"));
    }

    @Test
    void d2d_unitAnchoredIntegerInTraceNotFlagged() {
        // Prose says "149 MPa" — exactly in the trace. Don't flag.
        String hypothesis = "Hall-Petch yield strength scales with grain size.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Maximum yield stress 149 MPa [call_1] at d=20.75 µm.");
        String conclusion = "Hall-Petch confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}",
                        "max σ_y=149 MPa at d=20.75 µm.", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("149 MPa")
                && r.contains("derived-formula divergence"));
    }

    @Test
    void d2d_unitAnchoredIntegerIgnoresZeroAndOne() {
        // Don't flag "0 MPa" or "1 K" — common as baseline references.
        String hypothesis = "Test hypothesis.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Baseline 0 MPa and reference 1 K, no other values cited.");
        String conclusion = "Consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}",
                        "{\"value\": 100}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("derived-formula divergence"));
    }

    // ---- Phase 12 D2e: high-deviation trace results (P-a328-405 / P-a328-422) ----

    @Test
    void d2e_highDeviationFieldInTraceResultFlagged() {
        // P-a328-405 / P-a328-422 signature: trace's resultJson carries
        // "deviation": 99.9 — the recovery is unreliable even if the literal
        // numeric matches the prose.
        String hypothesis = "Beer-Lambert linearity holds for ε.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Recovered ε = 0.111 across five runs [call_1].");
        String conclusion = "Beer-Lambert confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "{\"epsilon\": 0.111, \"deviation\": 99.9}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("call_1")
                && r.contains("deviation")
                && r.contains("unreliable"));
    }

    @Test
    void d2e_lowDeviationFieldInTraceResultNotFlagged() {
        // Deviation 2.5% is well below the 50% threshold — don't flag.
        String hypothesis = "Beer-Lambert linearity holds.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Recovered ε = 0.111 [call_1].");
        String conclusion = "Confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "{\"epsilon\": 0.111, \"deviation\": 2.5}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("unreliable"));
    }

    @Test
    void d2e_errorPctFieldAlsoCaught() {
        // "error_pct" is an alternate spelling — also caught.
        String hypothesis = "Test.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Result 1.50 [call_1].");
        String conclusion = "Consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}",
                        "{\"value\": 1.50, \"error_pct\": 75.0}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("call_1")
                && r.contains("unreliable"));
    }
}
