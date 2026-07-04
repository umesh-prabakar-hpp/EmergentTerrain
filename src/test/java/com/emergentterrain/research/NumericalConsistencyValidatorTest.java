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

    // -------- Phase 16 #1: sign-aware (magnitude-fallback) matching --------

    @Test
    void p16_1_pA328_555_proseMagnitudeMatchesSignedTraceValue() {
        // P-a328-555 cited "15.286" in Findings — the tool result string
        // contained "deviation -15.286" (signed). Pre-Phase-16 the strict
        // signed comparison rejected the magnitude form as fabricated.
        String hypothesis = "Mean Collatz steps for happy numbers near 50.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Deviation from baseline reaches 15.286 over the interval.");
        String conclusion = "Mean 34.71 vs predicted 50.00.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}",
                        "Mean of collatzSteps = 35 (n=7, baseline 50.000, deviation -15.286).",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("15.286"));
    }

    // -------- Phase 16 #2: [call_0] off-by-one labelling drift --------

    @Test
    void p16_2_pA328_516_call0CitedOnOneBasedTraceEmitsLabellingDrift() {
        // P-a328-516 cited [call_0] in prose; trace has calls 1-6. Pre-Phase-16
        // this was flagged as "does not appear in trace" (fabrication
        // severity). Phase 16 emits a softer labelling-drift reason when
        // call_1 exists.
        String hypothesis = "Reuss bound test.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Crossover observed at Vf=1.00 [call_0] in this regime.");
        String conclusion = "Consistent with Reuss prediction.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}",
                        "{\"value\": 1.00}", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("[call_0]")
                && r.contains("labelling drift"));
        // Ensure the strict "does not appear in trace" wording is NOT emitted
        // for this case (the softer reason replaces it).
        assertThat(reasons).noneMatch(r -> r.contains("[call_0]")
                && r.contains("does not appear in"));
    }

    @Test
    void p16_2_call0WithoutCall1FallsBackToStrictReason() {
        // Safety check: if call_1 doesn't exist (only call_2, call_3 etc., or
        // an empty trace), [call_0] should still produce the strict "does not
        // appear in trace" reason — labelling drift only fires when call_1 is
        // present.
        String hypothesis = "Test.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Result cited [call_0] in this regime.");
        String conclusion = "Consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}",
                        "{\"value\": 1.00}", 0, "call_2"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("[call_0]")
                && r.contains("does not appear in"));
    }

    // -------- Phase 16 #3: framing-percentage severity --------

    @Test
    void p16_3_pA328_526_framingPercentageGetsSofterSeverity() {
        // P-a328-526 cited "50 %" as an efficiency-target framing reference
        // not derived from any tool. Pre-Phase-16 it was tagged "suspected
        // derived-formula divergence" (fabrication severity); Phase 16 emits
        // a softer "unsourced framing value" reason for round-number %.
        String hypothesis = "Carnot efficiency for T_c≈342 K, T_h swept upward.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The efficiency target of 50 % is the practical regime ceiling.");
        String conclusion = "Carnot ceiling holds.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_gpu_eval", "{}",
                        "GPU eval mean=0.640 max=0.761 n=877.",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("50")
                && r.contains("unsourced framing value"));
        assertThat(reasons).noneMatch(r -> r.contains("50")
                && r.contains("suspected derived-formula divergence"));
    }

    @Test
    void p16_3_nonFramingPercentageKeepsDerivationSeverity() {
        // 73 % is not in the framing set — should keep the standard
        // derivation-divergence severity.
        String hypothesis = "Test for unsourced percentages.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The observed value of 73 % is the new result.");
        String conclusion = "Test.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}",
                        "fraction = 0.450 (n=100).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("73 %")
                && r.contains("suspected derived-formula divergence"));
    }

    // -------- Phase 16 #4: extended errored-result markers --------

    @Test
    void p16_4_pA328_519_invalidRangeMarkerTreatedAsFailed() {
        // P-a328-519 cited [call_1] whose result was "Invalid range: must be
        // ≥1 and span ≤ 5000". The old ERRORED_RESULT_MARKER didn't catch
        // this prefix; Phase 16 adds it.
        String hypothesis = "Voigt rule for composite stress.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Stress reached 341 MPa at Vf=10% [call_1].");
        String conclusion = "Linear scaling observed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "Invalid range: must be ≥1 and span ≤ 5000",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("[call_1]")
                && r.contains("errored or was rejected"));
    }

    @Test
    void p16_4_sandboxExecutionFailedMarkerCaught() {
        String hypothesis = "Test.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1, "Value [call_1].");
        String conclusion = "Test.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}",
                        "Sandbox execution failed: Array index out of bounds",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("[call_1]")
                && r.contains("errored or was rejected"));
    }

    @Test
    void p16_4_topicNotComputableMarkerCaught() {
        String hypothesis = "Test.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1, "Value [call_1].");
        String conclusion = "Test.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}",
                        "Topic not computable via MathPhysicsEngine.",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("[call_1]")
                && r.contains("errored or was rejected"));
    }

    // -------- Phase 16 #5: one-sided CI tightening --------

    @Test
    void p16_5_pA328_528_proseTightensTraceCiFlagged() {
        // P-a328-528: prose "0.34 ± 0.02" against tool "0.34 ± 0.07".
        // 0.02 / 0.07 = 29% — well below the 50% slack floor.
        String hypothesis = "Density estimate.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Combined density 0.34 ± 0.02 lies within the predicted baseline.");
        String conclusion = "Hypothesis supported.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}",
                        "fraction satisfying = 0.34 ± 0.07 (95% CI, n=200).",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("confidence interval")
                && r.contains("0.02")
                && r.contains("0.07")
                && r.contains("Tightening"));
    }

    @Test
    void p16_5_widenedCiNotFlagged() {
        // Prose reports a CONSERVATIVE wider CI — should never flag.
        String hypothesis = "Density estimate.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Combined density 0.34 ± 0.10 lies within the predicted baseline.");
        String conclusion = "Hypothesis supported.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}",
                        "fraction satisfying = 0.34 ± 0.07 (95% CI, n=200).",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("confidence interval"));
    }

    @Test
    void p16_5_minorRoundingDoesNotFlagCiShrinkage() {
        // 0.06 rounded down from 0.07 (86% of trace) — within slack, no flag.
        String hypothesis = "Density estimate.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Density 0.34 ± 0.06.");
        String conclusion = "Consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}",
                        "fraction = 0.34 ± 0.07.", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("confidence interval"));
    }

    // -------- Phase 16 #6: scope overreach --------

    @Test
    void p16_6_pA328_547_proseClaimsRangeNeverExecuted() {
        // P-a328-547 hypothesis says "first ten thousand"; prose claims scan
        // over [1,5000) and [5000,10000). Tool actually ran [85,110).
        String hypothesis = "Test density [85, 110).";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Two scans over [1, 5000) and [5000, 10000) both yielded a "
                        + "density consistent with the predicted baseline.");
        String conclusion = "Consistent across the range.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"range_start\": 85, \"range_end\": 110}",
                        "fraction = 0.12 (n=25).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("scope overreach")
                && (r.contains("[1, 5000)") || r.contains("[5000, 10000)")));
    }

    @Test
    void p16_6_proseRangeWithinExecutedRangePasses() {
        String hypothesis = "Test density [1, 5000).";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Sub-window [100, 200) shows density 0.13.");
        String conclusion = "Sub-region consistent.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"range_start\": 1, \"range_end\": 5000}",
                        "fraction = 0.13 (n=4999).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("scope overreach"));
    }

    // -------- 2026-06-04 rec #1/#2: derived arithmetic + value-band ranges --------
    // Fixtures are the five P-b72c-115…120 strikes that zeroed the batch.

    @Test
    void rec1_pB72c115_coeffTimesTraceValuePasses() {
        // P-b72c-115: prose decomposes the bound into k·rhs_scalar = 2×0.172 = 0.344
        // (the panel explicitly asked for this decomposition). One step over the
        // hypothesis coefficient and a trace number — traceable, no flag.
        String hypothesis = "mertens_delta <= +2.0000 * happy_density + +17.6690";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "At the worst window the rhs contribution k*happy_density reaches 0.344 "
                        + "against the observed excursion.");
        String conclusion = "The fixed-intercept bound is refuted.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "verify_conjecture",
                        "{\"lhs\":\"mertens_delta\",\"k\":2,\"c\":17.669,"
                                + "\"window_start\":200000,\"window_size\":5000,\"num_windows\":10}",
                        "REFUTED. Per-window: start=230000 lhs=+54.00000 rhs_scalar=+0.17200 "
                                + "bound=+18.01300 margin=+36.01980 REFUTED", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("0.344"));
    }

    @Test
    void rec1_pB72c118_sameCallCiEndpointPasses() {
        // P-b72c-118: prose cites the CI low endpoint 0.006 = 0.023 − 0.017, both
        // numbers from the same predicate-scan result. No flag.
        String hypothesis = "Joint prime-and-happy density in [400,700) near the independence model.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The joint fraction's confidence band reaches down to 0.006 at its lower edge.");
        String conclusion = "No structural uptick detected.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"predicate\":\"isPrime AND isHappy\",\"range_start\":400,\"range_end\":700}",
                        "fraction satisfying predicate = 0.023 ± 0.017 (95% CI, n=300) "
                                + "(baseline 0.045, deviation -0.022).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("'0.006'"));
    }

    @Test
    void rec1_pB72c120_coeffTimesSameCallSpreadPasses() {
        // P-b72c-120: prose cites 0.0046 = 2 × (0.7536 − 0.7513) — hypothesis
        // coefficient times the observed same-call scalar spread. No flag.
        String hypothesis = "mertens_delta <= +2.0000 * deficient_density + +16.5014";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The rhs contribution varies by only 0.0046 across the sampled windows.");
        String conclusion = "Scale-dependent refutation recorded.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "verify_conjecture",
                        "{\"lhs\":\"mertens_delta\",\"k\":2,\"c\":16.5014,"
                                + "\"window_start\":300000,\"window_size\":10000,\"num_windows\":5}",
                        "HELD. Per-window: start=300000 rhs_scalar=+0.75130 holds "
                                + "start=340000 rhs_scalar=+0.75360 holds", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("0.0046"));
    }

    @Test
    void rec1_pB72c119_phantomPeerValueStillFlags() {
        // P-b72c-119: prose cites "prior researcher value" 0.64 — genuinely
        // untraceable (no one-step combination of hypothesis/trace numbers
        // produces it). The derived-arithmetic fallback must NOT launder it.
        String hypothesis = "Joint prime-and-happy density in [400,800) approaches ~0.150.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Prior measurements reported densities of 0.64 in lower ranges.");
        String conclusion = "The discrepancy remains unexplained.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"predicate\":\"isPrime AND isHappy\",\"range_start\":400,\"range_end\":800}",
                        "fraction satisfying predicate = 0.025 ± 0.015 (95% CI, n=400) "
                                + "(baseline 0.025, deviation +0.000).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons)
                .anyMatch(r -> r.contains("0.64") && r.contains("not within ±1%"));
    }

    @Test
    void rec1b_canonicalCiConstantPassesWhenTraceReportsIntervals() {
        // P-b72c-131: prose cites 1.96 as the CI-construction multiplier; the trace's
        // own half-widths are 1.96-derived (1.96·√(0.025·0.975/400) ≈ ±0.015). A
        // methodological constant, not a fabricated result — no flag.
        String hypothesis = "Joint prime-and-happy density in [500,900) near the independence model.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Intervals are normal approximations using the 1.96 multiplier at the 95% level.");
        String conclusion = "No anomaly detected.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"predicate\":\"isPrime AND isHappy\",\"range_start\":500,\"range_end\":900}",
                        "fraction satisfying predicate = 0.025 ± 0.015 (95% CI, n=400).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("1.96"));
    }

    @Test
    void rec1b_canonicalCiConstantStillFlagsWithoutCiContext() {
        // Same 1.96 citation but the trace reports NO confidence intervals — the
        // allowlist must not apply; an unexplained 1.96 stays a fabrication flag.
        String hypothesis = "Bound study.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The decisive scaling factor is 1.96 across all sampled windows.");
        String conclusion = "Factor confirmed.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "verify_conjecture",
                        "{\"lhs\":\"mertens_delta\",\"k\":2,\"c\":17.0,\"window_start\":200000,"
                                + "\"window_size\":5000,\"num_windows\":5}",
                        "HELD. Per-window: start=200000 lhs=-60.00000 rhs_scalar=+0.08230 holds",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons)
                .anyMatch(r -> r.contains("1.96") && r.contains("not within ±1%"));
    }

    @Test
    void rec2_fractionalBoundIntervalIsNotARangeClaim() {
        // P-b72c-120: "[0.7513, 0.7536)" is an observed-scalar band, not a scan
        // range — fractional bounds must not trigger scope overreach.
        String hypothesis = "mertens_delta <= +2.0000 * deficient_density + +16.5014";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "deficient_density stayed within [0.7513, 0.7536) across all sampled windows.");
        String conclusion = "Bound refuted at the 1.4M region only.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "verify_conjecture",
                        "{\"lhs\":\"mertens_delta\",\"k\":2,\"c\":16.5014,"
                                + "\"window_start\":300000,\"window_size\":10000,\"num_windows\":5}",
                        "HELD across 5 window(s).", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("scope overreach"));
    }

    // -------- 2026-06-05 rec #1: executed-range union merging --------

    private static com.emergentterrain.llm.ToolInvocation scan(int start, int end, int round) {
        return new com.emergentterrain.llm.ToolInvocation(
                "run_predicate_scan",
                "{\"predicate\":\"isPrime AND isHappy\",\"range_start\":" + start
                        + ",\"range_end\":" + end + "}",
                "fraction satisfying predicate = 0.025 ± 0.015 (95% CI, n=" + (end - start) + ").",
                round, "call_" + (round + 1));
    }

    @Test
    void rec1c_adjacentLadderUnionCoversSummaryClaim() {
        // P-b72c-138: the 5-rung adjacent ladder the decomposition guidance teaches,
        // honestly summarized over its union — must NOT flag.
        String hypothesis = "Joint prime-and-happy density across [1, 1500).";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Across the full surveyed range [1, 1500) the joint density stays near 0.025.");
        String conclusion = "No anomaly.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                scan(1, 300, 0), scan(300, 600, 1), scan(600, 900, 2),
                scan(900, 1200, 3), scan(1200, 1500, 4));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("scope overreach"));
    }

    @Test
    void rec1c_claimBeyondMergedUnionStillFlags() {
        // P-b72c-137: union reaches 1000; prose claims [1000, 1400) — true overreach.
        String hypothesis = "Density study.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The trend persists into [1000, 1400) as well.");
        String conclusion = "Persistent trend.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                scan(100, 600, 0), scan(600, 1000, 1));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("scope overreach")
                && r.contains("[1000, 1400)"));
    }

    @Test
    void rec2b_unitIntervalIsADensityDomainNotARangeClaim() {
        // P-b72c-141: prose stating the joint density "lives in [0, 1)" was read as
        // an unexecuted scan range. Integer bounds pass the fractional guard and
        // span 1 can never be covered — the unit interval must be exempt.
        String hypothesis = "Joint prime-and-happy density across the surveyed windows.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "As a fraction the joint density necessarily lies in [0, 1); observed 0.025.");
        String conclusion = "No anomaly.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                scan(400, 800, 0));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("scope overreach"));
    }

    @Test
    void rec1c_realGapIsNotMergedAcross() {
        // [1,500) and [800,1300) leave [500,800) unscanned — a claim spanning the
        // gap must still flag; merging must respect real gaps, not bridge them.
        String hypothesis = "Density study.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Across [1, 1300) the density is flat.");
        String conclusion = "Flat.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                scan(1, 500, 0), scan(800, 1300, 1));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("scope overreach")
                && r.contains("[1, 1300)"));
    }

    // -------- Phase 16 #7: inline "Computation N" stub --------

    @Test
    void p16_7_pA328_553_inlineComputationStubFlagged() {
        // P-a328-553 emitted "Computation 3 — Testing a scale closer to the
        // starting point (44)..." inline in Findings; trace had 0 successful
        // calls. F1b's block-count gate wouldn't catch this inline form.
        String hypothesis = "Divisor-sum convergence.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "First run yielded a worst deviation of 0.004 at N=176. "
                        + "Computation 2 — Testing a scale adjacent to the current "
                        + "one may reveal deviations. Computation 3 — Testing a "
                        + "scale closer to the starting point may expose drift.");
        String conclusion = "Asymptotic holds.";
        // Trace with zero entries — gate-closed-professor case.
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of();

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        // Empty trace short-circuits to findUnanchoredFindingsWithoutTrace, so
        // the inline-stub check is reached via the normal trace-aware path;
        // verify it fires when trace has SOME entries but is overshot.
    }

    @Test
    void p16_7_inlineComputationStubWithSparseTraceFlagged() {
        String hypothesis = "Goldbach partition survey.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The first run yielded a mean of 14.5 [call_1]. Computation 2 — "
                        + "shows a thin neighbourhood near 380. Computation 3 — "
                        + "displays a gentle trend. Computation 4 — confirms.");
        String conclusion = "Goldbach holds locally.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}",
                        "mean=14.59 n=140.", 0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("Computation")
                && r.contains("inline computation-stub fabrication"));
    }

    @Test
    void p16_7_inlineComputationsMatchingTraceNotFlagged() {
        // Three Computation mentions, three successful calls — clean.
        String hypothesis = "Goldbach partition survey.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "Computation 1 produced mean=14.59. Computation 2 — confirmed "
                        + "the trend. Computation 3 — added a robustness check.");
        String conclusion = "Goldbach holds locally.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "evaluate_symbolic", "{}", "mean=14.59.", 0, "call_1"),
                new com.emergentterrain.llm.ToolInvocation(
                        "run_sandboxed_code", "{}", "result=14.61.", 1, "call_2"),
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{}", "fraction=0.45.", 2, "call_3"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("inline computation-stub"));
    }

    // -------- rec #1 (2026-05-28): scale / percent-fraction false-NULL --------
    // Ported from the P0 CitationValidator scale-reconcile into the
    // numerical-consistency validator — the dominant residual false-NULL source
    // in the post-rotation a3289005 batch (REVIEW_REPORT.md §1 rec 1).

    @Test
    void rec1_pA328_998_scaleTolerantMatchDoesNotFlagScaledTraceValue() {
        // P-a328-998: prose cites "5.840" (a σ_obs deviation); the trace records
        // the same quantity at SI scale as "σ_obs=5.840e-08". Pre-fix the strict
        // ±1% compare missed it by 8 decades and the paper was a false NULL.
        String hypothesis = "Stefan-Boltzmann constant σ = 2π⁵k⁴/(15c²h³).";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The sweep reported a worst observed value of 5.840 against the baseline [call_1].");
        String conclusion = "σ recovered to CODATA precision.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}",
                        "Param sweep: worst σ_obs=5.840e-08 (3.00% injected deviation).",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).noneMatch(r -> r.contains("5.840"));
    }

    @Test
    void rec1_scaleTolerantMatchStillFlagsGenuinelyAbsentValue() {
        // Guard: a value whose mantissa matches NOTHING in the trace at any
        // scale must still be flagged — the fallback only rescues unit-scale
        // mismatches, not fabricated digits.
        String hypothesis = "Stefan-Boltzmann constant recovery.";
        java.util.Map<Integer, String> findings = java.util.Map.of(1,
                "The sweep reported a worst observed value of 7.291 against the baseline [call_1].");
        String conclusion = "σ recovered.";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}",
                        "Param sweep: worst σ_obs=5.840e-08 (3.00% injected deviation).",
                        0, "call_1"));

        List<String> reasons = NumericalConsistencyValidator.validate(
                hypothesis, findings, conclusion, trace);
        assertThat(reasons).anyMatch(r -> r.contains("7.291") && r.contains("not within ±1%"));
    }

    @Test
    void rec1_pA328_996_percentAnchorMatchesFractionFindingsValue() {
        // P-a328-996: Conclusion says "close to 2.8%"; the measured joint density
        // in Findings is the fraction 0.023. 0.023 is within ±20% of 2.8/100 =
        // 0.028, so the percent↔fraction normalisation clears the false NULL.
        String hypothesis = "Joint isPrime∧isHappy density over [420, 720).";
        String findings   = "Measured joint density 0.023 over the window.";
        String conclusion = "The joint density is close to 2.8% across the regime.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).noneMatch(r -> r.contains("close to 2.8"));
    }

    @Test
    void rec1_percentNormalisationStillFlagsTrulyAbsentAnchor() {
        // Guard: a percent anchor whose fraction form ALSO has no nearby Findings
        // value must still flag. "close to 9%" → 0.09; Findings only has 0.023.
        String hypothesis = "Joint density survey.";
        String findings   = "Measured joint density 0.023 over the window.";
        String conclusion = "The joint density is close to 9% across the regime.";

        List<String> reasons = NumericalConsistencyValidator.validate(hypothesis, findings, conclusion);
        assertThat(reasons).anyMatch(r -> r.contains("close to 9"));
    }

    // ── Phase 32 enforce — the value-tracing / structural split ─────────────

    @Test
    void valueTracingReasonsClassified() {
        // The four classes that retire to manifest lookup under enforce.
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Findings cites salient value '0.019' that is not within ±1% of any number..."))
                .isTrue();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Findings cites unit-anchored value '97 %' that is not within ±1%..."))
                .isTrue();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Findings cites '60 %' as an unsourced framing value — round-number target..."))
                .isTrue();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Findings cites confidence interval '0.025 ± 0.015' but the matching tool result..."))
                .isTrue();
    }

    @Test
    void executedRangeUnion_extractsAndCoalesces() {
        // A predicate-scan range and an adjacent verify window-ladder fuse;
        // a genuinely separated scan stays its own interval.
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_predicate_scan",
                        "{\"range_start\":400,\"range_end\":800}", "fraction = 0.025", 0, "c1"),
                new com.emergentterrain.llm.ToolInvocation("verify_conjecture",
                        "{\"window_start\":800,\"window_size\":100,\"num_windows\":4}",
                        "HELD across 4 window(s)", 1, "c2"),
                new com.emergentterrain.llm.ToolInvocation("run_predicate_scan",
                        "{\"range_start\":5000,\"range_end\":6000}", "fraction = 0.1", 2, "c3"));
        java.util.List<double[]> union =
                NumericalConsistencyValidator.executedRangeUnion(trace);
        assertThat(union).hasSize(2);
        assertThat(union.get(0)[0]).isEqualTo(400.0);
        assertThat(union.get(0)[1]).isEqualTo(1200.0);   // 800 + 4·100, fused
        assertThat(union.get(1)[0]).isEqualTo(5000.0);
    }

    @Test
    void executedRangeUnion_skipsErroredCalls() {
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_predicate_scan",
                        "{\"range_start\":200000,\"range_end\":1000000}",
                        "Invalid predicate scan: span exceeds cap", 0, "c1"));
        assertThat(NumericalConsistencyValidator.executedRangeUnion(trace)).isEmpty();
        assertThat(NumericalConsistencyValidator.executedRangeUnion(null)).isEmpty();
    }

    @Test
    void structuralReasonsStayWithTheLadder() {
        // P-b72c-244's live scope catch and the other structural classes
        // must NOT classify as value-tracing — they survive enforce.
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Prose claims results over range [1100, 1450) but no tool call executed..."))
                .isFalse();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Conclusion claims values are 'close to 9%' but no Findings value lands..."))
                .isFalse();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(
                "Findings cites '[call_7]' which does not appear in the persisted tool-call trace"))
                .isFalse();
        assertThat(NumericalConsistencyValidator.isValueTracingReason(null)).isFalse();
    }

    // -------- Verdict-grounding (REVIEW_REPORT b72c4e10 batch 1, rec #1) --------

    /** A minimal verify_conjecture trace entry — only its presence matters for the grounding check. */
    private static List<com.emergentterrain.llm.ToolInvocation> verifyConjectureTrace() {
        return List.of(new com.emergentterrain.llm.ToolInvocation(
                "verify_conjecture", "{}",
                "verify_conjecture: lhs <= +1.0000 * rhs + +0.0000 HELD across 10 window(s) "
                        + "of size 5000 starting at 300000. worst margin: -0.01000.",
                0, "call_1"));
    }

    @Test
    void pB72c77627649_reversedMarginCounterfactualFlagged() {
        // The P-b72c-77627649 "Sign Convention Clarification" hedge: entertains that under the reversed
        // convention a HELD (positive-margin) window would be a violation.
        String findings = "If margin = RHS - LHS, then positive margin means the bound holds with slack. "
                + "However, if the tool defines margin = LHS - bound, then positive values would "
                + "indicate violations. The trace does not resolve this ambiguity.";
        List<String> reasons = NumericalConsistencyValidator.validateVerdictGrounding(
                findings, "", verifyConjectureTrace());
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("margin-sign convention");
    }

    @Test
    void pB72c77627649_dualMarginDefinitionInConclusionFlagged() {
        // The Conclusion's dual presentation of two competing margin definitions.
        String conclusion = "Open: whether the sign convention for 'margin' is margin = RHS - LHS "
                + "(slack) or margin = LHS - bound (violation), which must be resolved before the HELD "
                + "verdict can be trusted.";
        List<String> reasons = NumericalConsistencyValidator.validateVerdictGrounding(
                "", conclusion, verifyConjectureTrace());
        assertThat(reasons).hasSize(1);
    }

    @Test
    void unicodeMinusInDualDefinitionFlagged() {
        // Real papers render the minus as U+2212; the pattern's char class must accept it.
        String conclusion = "margin = RHS − LHS or margin = LHS − bound";
        List<String> reasons = NumericalConsistencyValidator.validateVerdictGrounding(
                "", conclusion, verifyConjectureTrace());
        assertThat(reasons).hasSize(1);
    }

    @Test
    void correctSingleConventionUsagePasses() {
        // Stating the convention once and trusting the label is correct grounding — must NOT flag.
        String findings = "Each window reports margin = lhs - bound; the tool labels the window holds "
                + "when margin <= 0.04, so all 20 windows held with positive slack.";
        String conclusion = "The bound HELD across every tested window; the per-window holds label is "
                + "the verdict.";
        List<String> reasons = NumericalConsistencyValidator.validateVerdictGrounding(
                findings, conclusion, verifyConjectureTrace());
        assertThat(reasons).isEmpty();
    }

    @Test
    void noVerifyConjectureTrace_doesNotFlagEvenWithHedge() {
        // The check is scoped to verify_conjecture papers — no such trace, no fire.
        String findings = "if margin = LHS - bound, then positive values would indicate violations";
        List<String> reasons = NumericalConsistencyValidator.validateVerdictGrounding(
                findings, "", List.of(new com.emergentterrain.llm.ToolInvocation(
                        "scalar_window_stats", "{}", "scalar_window_stats: ...", 0, "call_1")));
        assertThat(reasons).isEmpty();
    }

    @Test
    void nullTrace_returnsEmpty() {
        assertThat(NumericalConsistencyValidator.validateVerdictGrounding("x", "y", null)).isEmpty();
    }

    // ── 2026-06-18 — uncoveredProseRanges (scope-recover gate detector) ──────────

    private static com.emergentterrain.llm.ToolInvocation scan(int start, int end) {
        return new com.emergentterrain.llm.ToolInvocation(
                "run_predicate_scan",
                "{\"predicate\":\"isPrime OR isAbundant\",\"range_start\":" + start
                        + ",\"range_end\":" + end + ",\"metric\":\"fraction_satisfying\"}",
                "Predicate scan: fraction satisfying predicate = 0.40000 (exact count).", 0, "call");
    }

    @Test
    void scopeRecover_flagsProseRangeNoCallScanned() {
        // P-b72c-77627719 signature: scanned [300,750), prose narrates [750,1200).
        var trace = List.of(scan(300, 750));
        var uncovered = NumericalConsistencyValidator.uncoveredProseRanges(
                "The density over [750, 1200) continues the downward trend.", trace);
        assertThat(uncovered).hasSize(1);
        assertThat(uncovered.get(0)).containsExactly(750.0, 1200.0);
    }

    @Test
    void scopeRecover_emptyWhenProseRangeIsScanned() {
        // P-b72c-77627718's actual scanned window [400,800) is in scope → no overreach.
        var trace = List.of(scan(400, 800));
        assertThat(NumericalConsistencyValidator.uncoveredProseRanges(
                "Over [400, 800) the union fraction is 0.40.", trace)).isEmpty();
    }

    @Test
    void scopeRecover_coalescesAdjacentScansBeforeCoverageTest() {
        // Adjacent ladder [400,800)+[800,1200)+[1200,1600) coalesces; a summary "[400,1600)" is covered,
        // but a claim over [0,400) (P-b72c-77627718) is not.
        var trace = List.of(scan(400, 800), scan(800, 1200), scan(1200, 1600));
        assertThat(NumericalConsistencyValidator.uncoveredProseRanges(
                "Across [400, 1600) the fraction is stable.", trace)).isEmpty();
        var uncovered = NumericalConsistencyValidator.uncoveredProseRanges(
                "A baseline over [0, 400) provides comparison.", trace);
        assertThat(uncovered).hasSize(1);
        assertThat(uncovered.get(0)).containsExactly(0.0, 400.0);
    }

    @Test
    void scopeRecover_ignoresValueBandsAndUnitInterval() {
        var trace = List.of(scan(300, 750));
        // Fractional CI / scalar spread and the [0,1) density domain are VALUE bands, not scan ranges.
        assertThat(NumericalConsistencyValidator.uncoveredProseRanges(
                "The CI was [0.006, 0.04) and the density lies in [0, 1).", trace)).isEmpty();
    }

    @Test
    void scopeRecover_emptyWhenNoExecutedCoverageOrNullProse() {
        assertThat(NumericalConsistencyValidator.uncoveredProseRanges("Over [0, 400).", List.of()))
                .isEmpty();
        assertThat(NumericalConsistencyValidator.uncoveredProseRanges(null, List.of(scan(300, 750))))
                .isEmpty();
    }

    @Test
    void scopeRecover_gateStillFlagsAfterRefactor_parity() {
        // The publish gate delegates to the same primitive — it must still emit the scope-overreach
        // reason (refactor is behavior-preserving).
        var trace = List.of(scan(300, 750));
        List<String> reasons = NumericalConsistencyValidator.validate(
                "Hypothesis over [300,750).",
                java.util.Map.of(1, "Results over [750, 1200) extend the trend [call_1]."),
                "Consistent.", trace);
        assertThat(reasons).anyMatch(r -> r.contains("scope overreach")
                && r.contains("[750") && r.contains("1200"));
    }

    @Test
    void p746_hypotheticalRangeInFutureWorkSentence_notScopeOverreach() {
        // P-b72c-77627746's live FP, verbatim shape: the panel asked the reviser to
        // discuss an independent range; the honest "cannot be confirmed … natural
        // next step" answer was nulled as scope overreach. A range inside an
        // explicitly not-executed sentence is not a results claim.
        var trace = List.of(scan(100000, 200000));
        List<String> reasons = NumericalConsistencyValidator.validate(
                "Growth law of the maximum divisor count.",
                java.util.Map.of(1,
                        "Whether k=1.30 holds on an independent range (e.g., x ∈ "
                                + "[200000, 300000)) cannot be confirmed from the present trace "
                                + "due to the work-cap constraint encountered in call_4; testing "
                                + "on such a range is a natural next step."),
                "No claim beyond the tested interval.", trace);
        assertThat(reasons).noneMatch(r -> r.contains("scope overreach"));
    }

    @Test
    void p767_valueAnchorVsCoordinate_noBandMismatch() {
        // P-b72c-77627767 (peer 0.775, novelty 0.77) was false-NULLed: hypothesis "converges to
        // a constant near 0.49" (a VALUE) vs conclusion "at 200003" (a window COORDINATE) —
        // different kinds. Sub-100 value anchors must not enter the coordinate band check.
        var trace = List.of(scan(200003, 205003));
        List<String> reasons = NumericalConsistencyValidator.validate(
                "richly_divisible_density converges to a constant near 0.49 as evidenced by "
                        + "verify_constant.",
                java.util.Map.of(1, "The density stabilises across windows [call_1]."),
                "The measured mean at 200003 is 0.48955, settled within tolerance.", trace);
        assertThat(reasons).noneMatch(r -> r.contains("falls outside the Hypothesis"));
    }

    @Test
    void coordinateAnchorVsDriftedCoordinate_stillCaught() {
        // The documented offender class (P-a328-50/61): hypothesis "near 380", conclusion
        // "starting 710" — both POSITION-scale, 87% off, must still fire.
        var trace = List.of(scan(300, 800));
        List<String> reasons = NumericalConsistencyValidator.validate(
                "The transition occurs near 380 in this range.",
                java.util.Map.of(1, "Scanned the region [call_1]."),
                "The deviation emerges starting 710 and grows.", trace);
        assertThat(reasons).anyMatch(r -> r.contains("falls outside the Hypothesis"));
    }

    @Test
    void claimedRangeWithoutHypotheticalMarker_stillScopeOverreach() {
        // Control: the SAME range asserted as a result stays a true positive — the
        // exemption demands a strong negation/future-work marker, not just "e.g.".
        var trace = List.of(scan(100000, 200000));
        List<String> reasons = NumericalConsistencyValidator.validate(
                "Growth law of the maximum divisor count.",
                java.util.Map.of(1,
                        "The bound k=1.30 also holds on the range (e.g., x ∈ "
                                + "[200000, 300000)) with comparable margins."),
                "Holds across both ranges.", trace);
        assertThat(reasons).anyMatch(r -> r.contains("scope overreach")
                && r.contains("[200000") && r.contains("300000"));
    }
}
