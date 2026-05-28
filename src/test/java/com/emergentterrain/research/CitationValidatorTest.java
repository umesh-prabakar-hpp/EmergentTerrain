package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 C3 — tests for the citation validator.
 *
 * <p>Each test constructs a small {@link NarrationResult} + matching
 * {@link ToolInvocation} trace, runs the validator, and asserts the
 * three failure counters and {@code hasFailures()} return the expected
 * shape.
 */
class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    private static ToolInvocation call(String callId, String resultJson) {
        return new ToolInvocation("run_sandboxed_code", "{}", resultJson, 0, callId);
    }

    private static NarrationResult.NumericClaim claim(double v, String callId, String loc) {
        return new NarrationResult.NumericClaim(v, "", callId, "test", loc);
    }

    private static NarrationResult narration(String hypothesis, Map<Integer, String> findings,
                                              String conclusion,
                                              List<NarrationResult.NumericClaim> claims) {
        return new NarrationResult(hypothesis, findings, conclusion, null, "test-model",
                0.8, List.of(), null, List.of(), List.of(),
                "test-id", "test-version", null, null, null, null, null, claims);
    }

    // ────────────────── Check 1: citation completeness ──────────────────

    @Test
    void cleanPaper_zeroFailures() {
        // 3 numbers, each followed by a [call_N] marker that resolves in trace.
        NarrationResult n = narration(
                "Hypothesis: at T=792 K [call_1].",
                Map.of(1, "Found ln(Kc/Kc0) = 7.553 [call_1]."),
                "Confirmed [call_1] within 4 sig figs.",
                List.of(
                        claim(792, "call_1", "hypothesis"),
                        claim(7.553, "call_1", "findings:1")
                ));
        List<ToolInvocation> trace = List.of(call("call_1", "T=792 K, ln=7.553"));
        var result = validator.validate(n, trace);
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.uncitedCount()).isZero();
        assertThat(result.unmatchedClaimCount()).isZero();
        assertThat(result.outOfToleranceCount()).isZero();
    }

    @Test
    void uncitedNumber_flagsAsUncited() {
        // 9471 K has no [call_N] marker — uncited.
        NarrationResult n = narration(
                "h",
                Map.of(1, "Result extended to 9471 K, beyond the swept range."),
                "c",
                List.of());
        var result = validator.validate(n, List.of(call("call_1", "T=2300..6100")));
        assertThat(result.uncitedCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("9471"));
        assertThat(result.hasFailures()).isTrue();
    }

    @Test
    void yearMention_isExcluded() {
        NarrationResult n = narration(
                "Van't Hoff's 1884 formulation predicts ln=7.553 [call_1].",
                Map.of(1, "ok"),
                "c",
                List.of(claim(7.553, "call_1", "hypothesis")));
        List<ToolInvocation> trace = List.of(call("call_1", "ln=7.553"));
        var result = validator.validate(n, trace);
        assertThat(result.uncitedCount()).isZero();
    }

    @Test
    void citationIndex_isExcluded() {
        NarrationResult n = narration(
                "Following [1], the value is 42.0 [call_1].",
                Map.of(1, "ok"),
                "c",
                List.of(claim(42.0, "call_1", "hypothesis")));
        List<ToolInvocation> trace = List.of(call("call_1", "value=42.0"));
        var result = validator.validate(n, trace);
        assertThat(result.uncitedCount()).isZero();
    }

    @Test
    void equationRef_isExcluded() {
        NarrationResult n = narration(
                "Per (eq. 3), the equilibrium is 7.553 [call_1].",
                Map.of(1, "ok"),
                "c",
                List.of(claim(7.553, "call_1", "hypothesis")));
        List<ToolInvocation> trace = List.of(call("call_1", "ln=7.553"));
        var result = validator.validate(n, trace);
        assertThat(result.uncitedCount()).isZero();
    }

    // ────────────────── Check 2: claim coverage ──────────────────

    @Test
    void citedButNoClaimEntry_flagsAsUnmatched() {
        // Number is cited [call_1] but no entry in numeric_claims covers it.
        NarrationResult n = narration(
                "h",
                Map.of(1, "Computed value 7.553 [call_1] matched the prediction."),
                "c",
                List.of()); // empty claims
        var result = validator.validate(n, List.of(call("call_1", "ln=7.553")));
        assertThat(result.unmatchedClaimCount()).isEqualTo(1);
        assertThat(result.uncitedCount()).isZero();
        assertThat(result.hasFailures()).isTrue();
    }

    @Test
    void claimValueMatchesProse_withinTolerance() {
        // Prose says 7.55; claim says 7.553 — within 0.5% tolerance, accepted.
        NarrationResult n = narration(
                "h",
                Map.of(1, "Computed value 7.55 [call_1]."),
                "c",
                List.of(claim(7.553, "call_1", "findings:1")));
        var result = validator.validate(n, List.of(call("call_1", "ln=7.553")));
        assertThat(result.hasFailures()).isFalse();
    }

    // ────────────────── Check 3: trace conformance ──────────────────

    @Test
    void claimReferencesMissingCallId_flagsOutOfTolerance() {
        NarrationResult n = narration(
                "h",
                Map.of(1, "Computed 7.553 [call_2]."),
                "c",
                List.of(claim(7.553, "call_2", "findings:1")));
        // Trace has call_1 but no call_2.
        var result = validator.validate(n, List.of(call("call_1", "ln=7.553")));
        assertThat(result.outOfToleranceCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("call_2"));
    }

    @Test
    void claimValueAbsentFromCallResult_flagsOutOfTolerance() {
        // call_2 exists in trace but its result doesn't contain 9471.
        NarrationResult n = narration(
                "h",
                Map.of(1, "Computed 9471 [call_2] far above the sweep."),
                "c",
                List.of(claim(9471, "call_2", "findings:1")));
        var result = validator.validate(n, List.of(
                call("call_1", "T=2300..6100"),
                call("call_2", "T=2300..6100, deviation=0.499%")));
        assertThat(result.outOfToleranceCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("9471"));
    }

    @Test
    void roundingTolerance_acceptsCloseEnoughValue() {
        // Tool result says 7.5530; claim says 7.553; prose says 7.55 [call_1].
        // All three within 0.5% — pass.
        NarrationResult n = narration(
                "h",
                Map.of(1, "ln value 7.55 [call_1]."),
                "c",
                List.of(claim(7.553, "call_1", "findings:1")));
        var result = validator.validate(n, List.of(call("call_1", "ln=7.5530, T=792")));
        assertThat(result.hasFailures()).isFalse();
    }

    // ────────────────── pre-Phase-11 short-circuit ──────────────────

    @Test
    void prePhase11Trace_shortCircuitsToClean() {
        // Trace entries with callId=null (legacy persisted papers). Validator
        // must return clean rather than spuriously flagging every number.
        ToolInvocation legacy = new ToolInvocation("run_sandboxed_code", "{}", "result", 0);
        NarrationResult n = narration(
                "h",
                Map.of(1, "fabricated 9471 with no citations and no markers"),
                "c",
                List.of());
        var result = validator.validate(n, List.of(legacy));
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    void noTrace_shortCircuitsToClean() {
        // First-pass-only paper (professor stage skipped). No tool trace, no
        // citations expected. Validator returns clean.
        NarrationResult n = narration("h", Map.of(1, "f"), "c", List.of());
        var result = validator.validate(n, List.of());
        assertThat(result.hasFailures()).isFalse();
    }

    // ────────────────── tolerance arithmetic ──────────────────

    @Test
    void tolerance_acceptsHalfPercentDeviation() {
        assertThat(CitationValidator.withinTolerance(7.55, 7.553)).isTrue();
        assertThat(CitationValidator.withinTolerance(99.5, 100.0)).isTrue();
    }

    @Test
    void tolerance_rejectsTenPercentDeviation() {
        assertThat(CitationValidator.withinTolerance(8.0, 7.0)).isFalse();
        assertThat(CitationValidator.withinTolerance(900, 1000)).isFalse();
    }

    @Test
    void tolerance_handlesZero() {
        assertThat(CitationValidator.withinTolerance(0.0, 0.0)).isTrue();
        assertThat(CitationValidator.withinTolerance(1e-12, 0.0)).isTrue();
        assertThat(CitationValidator.withinTolerance(0.5, 0.0)).isFalse();
    }

    // ────────────────── extractNumbers edges ──────────────────

    @Test
    void extractNumbers_skipsNullAndBlank() {
        assertThat(CitationValidator.extractNumbers(null, "x")).isEmpty();
        assertThat(CitationValidator.extractNumbers("", "x")).isEmpty();
        assertThat(CitationValidator.extractNumbers("   ", "x")).isEmpty();
    }

    @Test
    void valueAppearsInResult_searchesNumericTokens() {
        assertThat(CitationValidator.valueAppearsInResult(7.553,
                "Param sweep: ln=7.5530, T=792.0")).isTrue();
        assertThat(CitationValidator.valueAppearsInResult(9471,
                "Param sweep: ln=7.5530, T=792.0")).isFalse();
        assertThat(CitationValidator.valueAppearsInResult(792,
                "Param sweep: ln=7.5530, T=792.0")).isTrue();
    }

    // ────────────────── end-to-end-ish replay shape ──────────────────

    @Test
    void replayShape_p389_arrheniusAllToolsFailed() {
        // Reproduces P-a328-389's pattern: all 5 tool calls failed, but the
        // prose claims numeric ratios for 9 Ea values. With the C1 callId
        // stamping, those failed calls still get IDs — but the validator
        // finds zero numeric matches in their result text. Every cited
        // number flags as out_of_tolerance; uncited numbers flag as uncited.
        // Either way the paper fails the gate.
        NarrationResult n = narration(
                "h",
                Map.of(1, "Across Ea=10 [call_1] to Ea=10 kJ/mol [call_1] the ratio is 1.0 [call_1]; "
                        + "Ea=20 [call_2] gives 1.13 [call_2]."),
                "c",
                List.of(
                        claim(10, "call_1", "findings:1"),
                        claim(1.0, "call_1", "findings:1"),
                        claim(20, "call_2", "findings:1"),
                        claim(1.13, "call_2", "findings:1")
                ));
        List<ToolInvocation> trace = List.of(
                call("call_1", "Invalid range: range_start=range_end=10"),
                call("call_2", "Invalid range: range_start=range_end=20"));
        var result = validator.validate(n, trace);
        // Calls exist but their results contain only the parameter that was
        // rejected (10, 20) — not the fabricated ratios (1.0, 1.13).
        assertThat(result.outOfToleranceCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.hasFailures()).isTrue();
    }

    @Test
    void replayShape_p379_wienPeak_extrapolation() {
        // Reproduces P-a328-379's pattern: tool 1 swept T=2300-6100 K, prose
        // claims a worst-case data point at 9471 K (never computed). Either
        // the marker is missing (uncited) or, if the LLM cites [call_1], the
        // value isn't in call_1's output (out_of_tolerance).
        NarrationResult n = narration(
                "h",
                Map.of(1, "The worst-case point at 9471 K [call_1] showed strongest deviation."),
                "c",
                List.of(claim(9471, "call_1", "findings:1")));
        List<ToolInvocation> trace = List.of(
                call("call_1", "Sweep T=2300-6100 K, 40 samples, max_deviation=0.499%"));
        var result = validator.validate(n, trace);
        assertThat(result.outOfToleranceCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("9471"));
    }

    // ────────────────── Phase 21 §21.6: sweep-param whitelist ──────────────────

    @Test
    void sweepParamWhitelist_exemptsUncitedHypothesisNumber() {
        // Hypothesis cites "starting position of 17" and "scale of 7.4" — both
        // are PaperDraft sweep params, neither has a [call_N] marker, yet the
        // tool call DID run on those params. Pre-21.6 these counted as uncited;
        // with the whitelist they are exempt.
        NarrationResult n = narration(
                "Hypothesis: starting position of 17, scale of 7.4, expected T=792 K [call_1].",
                Map.of(1, "Found T=792 K [call_1]."),
                "Confirmed [call_1].",
                List.of(claim(792, "call_1", "findings:1")));
        List<ToolInvocation> trace = List.of(call("call_1", "T=792"));
        var result = validator.validate(n, trace, java.util.Set.of(17.0, 7.4));
        assertThat(result.uncitedCount()).isZero();
        assertThat(result.exemptSweepCount()).isEqualTo(2);
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    void sweepParamWhitelist_doesNotExemptUnrelatedUncitedNumber() {
        // 9471 K is NOT in the whitelist — must still flag as uncited.
        // No [call_N] in the hypothesis so the sweep params are truly uncited
        // (rather than picked up by proximity-to-marker).
        NarrationResult n = narration(
                "Hypothesis: starting position of 17, scale of 7.4.",
                Map.of(1, "Result extended to 9471 K, beyond the swept range."),
                "c",
                List.of());
        var result = validator.validate(n, List.of(call("call_1", "T=2300..6100")),
                java.util.Set.of(17.0, 7.4));
        assertThat(result.uncitedCount()).isEqualTo(1);
        assertThat(result.exemptSweepCount()).isEqualTo(2);
        assertThat(result.issues()).anyMatch(s -> s.contains("9471"));
        // The exempted values do NOT show up in issues — they're silent.
        assertThat(result.issues()).noneMatch(s -> s.contains(" 17 "));
        assertThat(result.issues()).noneMatch(s -> s.contains("7.4"));
    }

    @Test
    void sweepParamWhitelist_emptySetWithHypothesisExemption() {
        // Same paper with no sweep-param whitelist. Pre-2026-05-20 behavior:
        // 17, 7.4, and 9471 would all be uncited. Post-2026-05-20: 17 and
        // 7.4 are hypothesis-source (always exempt by location), only 9471
        // (in findings) remains uncited.
        NarrationResult n = narration(
                "Hypothesis: starting position of 17, scale of 7.4.",
                Map.of(1, "Result extended to 9471 K, beyond the swept range."),
                "c",
                List.of());
        var resultEmpty = validator.validate(n, List.of(call("call_1", "T=2300..6100")),
                java.util.Set.of());
        assertThat(resultEmpty.uncitedCount()).isEqualTo(1);
        assertThat(resultEmpty.hypothesisParamExemptCount()).isEqualTo(2);
        assertThat(resultEmpty.exemptSweepCount()).isZero();
        // Two-arg overload behaves identically to empty-set three-arg.
        var resultBackCompat = validator.validate(n,
                List.of(call("call_1", "T=2300..6100")));
        assertThat(resultBackCompat.uncitedCount()).isEqualTo(resultEmpty.uncitedCount());
        assertThat(resultBackCompat.hypothesisParamExemptCount())
                .isEqualTo(resultEmpty.hypothesisParamExemptCount());
    }

    // ───────── hypothesis-source exemption (2026-05-20) ─────────

    @Test
    void hypothesisExemption_P751Shape_paperCleansUp() {
        // P-a328-751 archetype: T and L appear uncited in the hypothesis;
        // tool consumes both as args; Findings cites the derived results
        // with [call_N]. Pre-fix the hypothesis-side T/L counted as
        // uncited → downgrade. Post-fix the hypothesis-source rule
        // exempts them and the paper publishes clean.
        // Findings prose places [call_1] within ~25 chars of every prose
        // number so the proximity marker rule (MARKER_LOOKAHEAD=25)
        // attributes each one to the right tool call.
        NarrationResult n = narration(
                "For an electron in a one-dimensional infinite well at T = 450 K, "
                        + "the ground-state energy increases as well width L = 4.58 nm decreases.",
                Map.of(1, "E1 = 2.872e-21 J [call_1] and ratio = 0.462 [call_1]."),
                "Tested; ratio [call_1] indicates suppressed-but-not-absent quantum regime.",
                List.of(
                        claim(2.872e-21, "call_1", "findings:1"),
                        claim(0.462, "call_1", "findings:1")));
        var result = validator.validate(n,
                List.of(call("call_1", "E1=2.872e-21, ratio=0.462281")));
        assertThat(result.uncitedCount()).isZero();
        assertThat(result.unmatchedClaimCount()).isZero();
        assertThat(result.outOfToleranceCount()).isZero();
        assertThat(result.hypothesisParamExemptCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    void hypothesisExemption_doesNotMaskUncitedInFindings() {
        // Hypothesis-side numbers are exempt by location. Findings-side
        // numbers without a [call_N] marker are still uncited — the
        // exemption must be location-scoped, not global.
        NarrationResult n = narration(
                "Hypothesis: at T = 800 K, the constant is 42.",
                Map.of(1, "Result extended to 9471 K beyond the swept range."),
                "c",
                List.of());
        var result = validator.validate(n, List.of(call("call_1", "T=2300..6100")));
        assertThat(result.uncitedCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("9471"));
        assertThat(result.hypothesisParamExemptCount()).isEqualTo(2);  // 800 + 42
    }

    @Test
    void hypothesisExemption_doesNotShortCircuitTraceConformance() {
        // Sanity: the exemption only silences Check 1 at the hypothesis
        // location. If the narration *claims* a value belongs to call_1
        // and that value isn't in call_1's result, out_of_tolerance still
        // fires. Fabrication-via-claims path stays guarded.
        NarrationResult n = narration(
                "Hypothesis: T = 450 K.",
                Map.of(1, "We measured ratio = 0.999 [call_1]."),
                "c",
                List.of(claim(0.999, "call_1", "findings:1")));
        var result = validator.validate(n,
                List.of(call("call_1", "E1=2.872e-21, ratio=0.462281")));
        assertThat(result.outOfToleranceCount()).isEqualTo(1);
        assertThat(result.issues()).anyMatch(s -> s.contains("0.999"));
    }

    @Test
    void sweepParamWhitelist_tolerantToFloatingPointWidening() {
        // PaperDraft.param1 = int 17 → whitelist holds 17.0; prose has "17"
        // (parsed by validator as 17.0). withinTolerance(17.0, 17.0) must pass.
        NarrationResult n = narration(
                "starting position of 17, scale of 7.4",
                Map.of(1, "ok [call_1]"),
                "c",
                List.of());
        var result = validator.validate(n, List.of(call("call_1", "ok")),
                java.util.Set.of(17.0, 7.4));
        assertThat(result.exemptSweepCount()).isEqualTo(2);
        assertThat(result.uncitedCount()).isZero();
    }

    // ───────── reconcile-before-null (2026-05-26, recommendation #1) ─────────

    @Test
    void reconcile_uncitedButTraceable_notFailedWhenEnabled() {
        // P-a328-960 shape: findings states the computed ratio without a
        // [call_N] marker, but the value IS in the trace. Strict mode flags it
        // uncited (→ null downgrade); reconcile mode treats it as traceable.
        NarrationResult n = narration(
                "h",
                Map.of(1, "E1/kBT reaches 2.07168 at the narrowest sampled width."),
                "c",
                List.of());
        List<ToolInvocation> trace = List.of(call("call_4", "result=2.07168 iters_used=20"));

        var strict = validator.validate(n, trace, java.util.Set.of(), false);
        assertThat(strict.uncitedCount()).isEqualTo(1);
        assertThat(strict.hasFailures()).isTrue();

        var reconciled = validator.validate(n, trace, java.util.Set.of(), true);
        assertThat(reconciled.uncitedCount()).isZero();
        assertThat(reconciled.hasFailures()).isFalse();
    }

    @Test
    void reconcile_misAttributedClaim_reconciledWhenEnabled() {
        // The claim cites call_2 (absent from the trace), but its value is
        // present in call_4 — a mis-attributed marker, not a fabrication.
        NarrationResult n = narration(
                "h",
                Map.of(1, "The ratio is 2.07168 [call_2]."),
                "c",
                List.of(claim(2.07168, "call_2", "findings:1")));
        List<ToolInvocation> trace = List.of(call("call_4", "result=2.07168"));

        var strict = validator.validate(n, trace, java.util.Set.of(), false);
        assertThat(strict.outOfToleranceCount()).isEqualTo(1);

        var reconciled = validator.validate(n, trace, java.util.Set.of(), true);
        assertThat(reconciled.outOfToleranceCount()).isZero();
        assertThat(reconciled.hasFailures()).isFalse();
    }

    @Test
    void reconcile_valueAbsentFromTrace_stillFails() {
        // The load-bearing safety property: reconcile must NOT launder a
        // hallucinated value. P-a328-960 cited "3.25" (borrowed from a sibling
        // paper) which is in NO trace result — it must still fail even with
        // reconcile enabled.
        NarrationResult n = narration(
                "h",
                Map.of(1, "The crossover is near 3.25 nm."),
                "c",
                List.of());
        List<ToolInvocation> trace = List.of(call("call_4", "result=2.07168"));

        var reconciled = validator.validate(n, trace, java.util.Set.of(), true);
        assertThat(reconciled.uncitedCount()).isEqualTo(1);
        assertThat(reconciled.issues()).anyMatch(s -> s.contains("3.25"));
        assertThat(reconciled.hasFailures()).isTrue();
    }

    @Test
    void reconcile_scaledPhysicalValue_matchesUnscaledTraceValue() {
        // P-a328-987 shape (the dominant false-NULL cause): the sandbox records
        // SCALED values for readability — recordSample(kT*1e21) → 6.95847,
        // recordSample(E1*1e21) → 1.35014 — while the prose reports the physical
        // quantities 6.958e-21 J and 1.350e-21 J. Strict matching misses them by
        // 21 orders of magnitude and the paper is wrongly nulled. Reconcile mode
        // must recognise them as the same computed values.
        NarrationResult n = narration(
                "h",
                Map.of(1, "kBT = 6.958e-21 J and the ground-state energy E1 = 1.350e-21 J."),
                "c",
                List.of());
        List<ToolInvocation> trace = List.of(call("call_5",
                "result=6.95847 named={kT_1e21=6.95847, E1_min_1e21=1.35014, ratio_at_Lmin=0.194029}"));

        var strict = validator.validate(n, trace, java.util.Set.of(), false);
        assertThat(strict.uncitedCount()).isEqualTo(2);
        assertThat(strict.hasFailures()).isTrue();

        var reconciled = validator.validate(n, trace, java.util.Set.of(), true);
        assertThat(reconciled.uncitedCount()).isZero();
        assertThat(reconciled.hasFailures()).isFalse();
    }

    @Test
    void reconcile_scaleTolerant_doesNotRescueWrongMantissa() {
        // Safety: the scale-invariant fallback matches significant digits, not
        // magnitude alone. A prose value whose mantissa is absent from the trace
        // stays flagged even though a same-magnitude number exists. Here prose
        // 8.337e-22 (E1 at the wide well, never recorded) must NOT be rescued by
        // the trace's 1.35014 / 6.95847 (different mantissas).
        NarrationResult n = narration(
                "h",
                Map.of(1, "The wide-well energy was 8.337e-22 J."),
                "c",
                List.of());
        List<ToolInvocation> trace = List.of(call("call_5",
                "named={kT_1e21=6.95847, E1_min_1e21=1.35014, ratio_at_Lmin=0.194029}"));

        var reconciled = validator.validate(n, trace, java.util.Set.of(), true);
        assertThat(reconciled.uncitedCount()).isEqualTo(1);
        assertThat(reconciled.issues()).anyMatch(s -> s.contains("8.337e-22"));
        assertThat(reconciled.hasFailures()).isTrue();
    }

    // ───────── rec #9: rejection diagnostics ─────────

    @Test
    void nearestTraceValue_picksClosestByRelativeGap() {
        var near = CitationValidator.nearestTraceValue(9471,
                java.util.List.of("computed a=100, b=9500, c=40"));
        assertThat(near).isPresent();
        assertThat(near.getAsDouble()).isEqualTo(9500.0);  // relΔ 0.003 beats 100 and 40
    }

    @Test
    void nearestTraceValue_emptyWhenNoNumbers() {
        assertThat(CitationValidator.nearestTraceValue(1.0, java.util.List.of("no numbers here")))
                .isEmpty();
        assertThat(CitationValidator.nearestTraceValue(1.0, java.util.List.of())).isEmpty();
    }

    @Test
    void relativeGap_zeroForEqual_oneForOrderOfMagnitude() {
        assertThat(CitationValidator.relativeGap(5.0, 5.0)).isZero();
        assertThat(CitationValidator.relativeGap(0.0, 0.0)).isZero();
        // A genuinely-absent hallucinated value sits far from its nearest token.
        assertThat(CitationValidator.relativeGap(3.25, 2.07168)).isGreaterThan(0.3);
        // A scale miss (same digits, ×1e21) is near-1 on raw gap — the signature
        // the scale-tolerant reconcile catches and this diagnostic makes visible.
        assertThat(CitationValidator.relativeGap(6.958e-21, 6.95847)).isCloseTo(
                1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void valueAppearsInResultScaleTolerant_unit() {
        // Direct digit/scale checks on the matcher.
        assertThat(CitationValidator.valueAppearsInResultScaleTolerant(
                6.958e-21, "named={kT_1e21=6.95847}")).isTrue();          // ×1e21 scale
        assertThat(CitationValidator.valueAppearsInResultScaleTolerant(
                2.94e-9, "L_threshold_nm=2.94245")).isTrue();            // nm→m scale
        assertThat(CitationValidator.valueAppearsInResultScaleTolerant(
                8.337e-22, "kT_1e21=6.95847, E1_min_1e21=1.35014")).isFalse(); // wrong digits
        assertThat(CitationValidator.valueAppearsInResultScaleTolerant(
                3.25, "result=2.07168")).isFalse();                     // hallucinated value
    }
}
