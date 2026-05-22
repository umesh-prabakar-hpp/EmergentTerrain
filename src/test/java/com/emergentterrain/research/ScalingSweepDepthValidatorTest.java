package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-21 review §1 rec #7 — unit tests for {@link
 * ScalingSweepDepthValidator}. Canonical signature: P-a328-884 (Hall-Petch)
 * tested 2 grain sizes and claimed σ scales as σ₀ + k·d^(−1/2). 2 points
 * can fit any linear function; the validator flags as structurally
 * insufficient.
 */
class ScalingSweepDepthValidatorTest {

    private static ToolInvocation sandbox(String resultText) {
        return new ToolInvocation("run_sandboxed_code",
                "{\"code\":\"return sigma_0 + k/sqrt(d);\"}",
                resultText, 0, "call_X");
    }

    private static ToolInvocation symbolic(String resultText) {
        return new ToolInvocation("run_symbolic",
                "{\"expression\":\"d^(-1/2)\"}",
                resultText, 0, "call_X");
    }

    private static ToolInvocation sweepCall() {
        return new ToolInvocation("run_param_sweep",
                "{\"start\":0,\"end\":100}",
                "swept", 0, "call_X");
    }

    private static ToolInvocation predicateScan() {
        return new ToolInvocation("run_predicate_scan",
                "{\"baseline\":0}",
                "scanned", 0, "call_X");
    }

    @Test
    void p884SignatureFlagsTwoPointSweep() {
        // Hall-Petch: 2 sandbox calls, scaling-as-power-law claim. → flag.
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "The yield strength scales as σ₀ + k·d^(-1/2) for fine grain regimes.",
                Map.of(1, "At d=0.05 µm: 2557.67 MPa. At d=1 µm: 648 MPa."),
                "Mathematically consistent across the tested grain sizes.",
                List.of(sandbox("2557.67"), sandbox("648.0")));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("scales as")
                .contains("2 non-sweep call(s)")
                .contains("at least 3");
    }

    @Test
    void threeSandboxCallsWithScalingClaimPasses() {
        // Same claim, but 3 sandbox calls — meets the minimum depth.
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "σ scales as σ₀ + k·d^(-1/2).",
                Map.of(1, "Three grain sizes tested."),
                "Linear in d^(-1/2).",
                List.of(sandbox("a"), sandbox("b"), sandbox("c")));
        assertThat(reasons).isEmpty();
    }

    @Test
    void sweepToolBypassesDepthRequirement() {
        // 1 sweep-tool call internally iterates over many values — equivalent
        // to a sweep, so a scaling claim is supported regardless of trace size.
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Density follows the Hardy-Littlewood prediction.",
                Map.of(1, "Sweep across [N, N+1000)."),
                "Within the conjectured bound.",
                List.of(predicateScan()));
        assertThat(reasons).isEmpty();
    }

    @Test
    void paramSweepBypassesDepthRequirement() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Hall-Petch scaling holds.",
                Map.of(1, "Swept grain sizes."),
                "Scaling confirmed.",
                List.of(sweepCall()));
        assertThat(reasons).isEmpty();
    }

    @Test
    void twoCallsWithoutScalingClaimPasses() {
        // 2 sandbox calls but no scaling claim — paper reports a closed-form
        // evaluation rather than a scaling relation. → no flag.
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Compute E1 for L=5.119 nm and T=439 K.",
                Map.of(1, "Computed value 0.379."),
                "Hypothesis confirmed within 1%.",
                List.of(sandbox("0.379"), symbolic("0.379")));
        assertThat(reasons).isEmpty();
    }

    @Test
    void oneCallWithoutScalingClaimPasses() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Optimal launch angle is 27°.",
                Map.of(1, "Optimum at 27°."),
                "Within drag-shifted range.",
                List.of(sandbox("27")));
        assertThat(reasons).isEmpty();
    }

    @Test
    void linearityClaimWithTwoCallsFlags() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Hypothesis predicts linearity in d^(-1/2).",
                Map.of(1, "Two values computed."),
                "Linear trend observed.",
                List.of(sandbox("a"), sandbox("b")));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("linearity in");
    }

    @Test
    void arrheniusClaimWithTwoCallsFlags() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Reaction rate follows Arrhenius law at high Ea.",
                Map.of(1, "Two temperatures evaluated."),
                "Within expected band.",
                List.of(sandbox("a"), sandbox("b")));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("Arrhenius");
    }

    @Test
    void emptyTraceReturnsEmpty() {
        // Empty trace is the missing-methods gate's territory; this validator
        // defers and returns empty so we don't double-flag.
        assertThat(ScalingSweepDepthValidator.validate(
                "σ scales as d^(-1/2).", Map.of(), "claim", List.of()))
                .isEmpty();
    }

    @Test
    void nullTraceReturnsEmpty() {
        assertThat(ScalingSweepDepthValidator.validate(
                "σ scales as d^(-1/2).", Map.of(), "claim", null))
                .isEmpty();
    }

    @Test
    void nullProseReturnsEmpty() {
        assertThat(ScalingSweepDepthValidator.validate(
                null, null, null,
                List.of(sandbox("a"), sandbox("b"))))
                .isEmpty();
    }

    @Test
    void powerLawHyphenatedMatches() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "Density follows a power-law decay.",
                Map.of(1, "Two values tested."),
                "Decay observed.",
                List.of(sandbox("a"), sandbox("b")));
        assertThat(reasons).hasSize(1);
    }

    @Test
    void inverseSquareRootMatches() {
        List<String> reasons = ScalingSweepDepthValidator.validate(
                "σ_y is inversely proportional to sqrt(d).",
                Map.of(1, "Hall-Petch checked at d=0.05 and d=1."),
                "Verified at both points.",
                List.of(sandbox("a"), sandbox("b")));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("inversely proportional");
    }
}
