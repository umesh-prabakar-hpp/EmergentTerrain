package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-08 — fabricated tool-output detector (motivating case P-b72c-77627413:
 * prose claimed "call_1 returned 452.74" but call_1 returned 2.18478).
 */
class ToolOutputAttributionValidatorTest {

    private static ToolInvocation call(String id, String resultJson) {
        return new ToolInvocation("run_sandboxed_code", "{}", resultJson, 0, id);
    }

    @Test
    void p413_fabricatedOutputAttribution_flagged() {
        String findings = "The sandboxed computation call_1 returned 452.74 as its "
                + "primary output, reflecting a pipeline failure.";
        List<ToolInvocation> trace = List.of(call("call_1",
                "{\"result\":2.18478,\"mean\":2.18478,\"count\":15}"));
        List<String> reasons = ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace);
        assertThat(reasons).anyMatch(r -> r.startsWith("tool_output_attribution:fabricated_output")
                && r.contains("call_1") && r.contains("452.74"));
    }

    @Test
    void honestOutputAttribution_passes() {
        String findings = "Call 1 returned 2.18478 for the mean omega [call_1].";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"result\":2.18478}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void honestRoundingWithinTolerance_passes() {
        // prose rounds 2.18478 → 2.18 (within 0.5%) — not a fabrication.
        String findings = "call_1 returned 2.18 across the qualifying set.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"mean\":2.18478}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void claimAgainstAMissingCall_flagged() {
        String findings = "call_9 returned 452.74 in the held-out region.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"mean\":2.18}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace))
                .anyMatch(r -> r.startsWith("tool_output_attribution:missing_call"));
    }

    @Test
    void derivedNumberAfterTheRealOutput_isNotAttributed() {
        // The verb→number adjacency attributes only 2.18 (the real output); the
        // downstream "11" is not pinned to call_1, so no false fabrication flag.
        String findings = "call_1 returned 2.18, implying 11 happy primes in range.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"mean\":2.18478}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void bareSmallIntegerCount_isNotSalient_passes() {
        // "returned 2 values" — a structural count, not an output value; skipped.
        String findings = "call_1 returned 2 values for the sweep.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"mean\":99.9}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void noVerb_noAttribution() {
        // "call_1 [call_1] and a value 452.74" — no output verb adjacency, no claim.
        String findings = "The scan at call_1 [call_1] sat near 452.74 by coincidence.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"mean\":2.18}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void nullOrEmptyInputs_returnEmpty() {
        assertThat(ToolOutputAttributionValidator.validate(java.util.Map.of(), "", List.of())).isEmpty();
        assertThat(ToolOutputAttributionValidator.validate(null, null, null)).isEmpty();
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, "call_1 returned 452.74."), "", List.of())).isEmpty();
    }

    // ── 2026-06-09 burn-in FP fixes (P-b72c-77627448) ────────────────────────

    @Test
    void showsACorrelationOverACoordinateRange_isNotFlagged() {
        // "shows" describes a revealed correlation/range, not a scalar output;
        // and "200,000" is a window coordinate (present in call_4's result).
        String findings = "For comparison, [call_4] shows that at x=200,000-299,000 "
                + "the Pearson correlation is +0.31.";
        List<ToolInvocation> trace = List.of(call("call_4",
                "scalar_window_stats over 100 windows of size 1000 starting at 200000. correlation +0.31"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void commaGroupedNumberParsesWhole_notTruncated() {
        // "200,000" must parse as 200000 (in the result), not "200" (which isn't),
        // so it does not falsely flag.
        String findings = "call_1 returned 200,000 scanned integers in the sweep.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"scanned\":200000}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace)).isEmpty();
    }

    @Test
    void commaGroupedFabrication_stillFlagged() {
        // A genuinely fabricated comma-number IS still caught.
        String findings = "call_1 returned 5,000,000 as the divisor sum.";
        List<ToolInvocation> trace = List.of(call("call_1", "{\"D\":77896938}"));
        assertThat(ToolOutputAttributionValidator.validate(
                java.util.Map.of(1, findings), "", trace))
                .anyMatch(r -> r.startsWith("tool_output_attribution:fabricated_output"));
    }
}
