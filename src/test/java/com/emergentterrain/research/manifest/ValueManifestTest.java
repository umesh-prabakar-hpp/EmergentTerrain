package com.emergentterrain.research.manifest;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 32 Wave 0 (32.1) — capture + resolve semantics, pinned to live
 * fixtures from the 2026-06-06 review batches.
 */
class ValueManifestTest {

    private static ToolInvocation verify(String result) {
        return new ToolInvocation("verify_conjecture", "{}", result, 0, "call_1");
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    @Test
    void capturesHypothesisConstantsAndToolValuesWithRenderings() {
        // P-b72c-211's shape: hypothesis carries k and c; the result carries
        // signed renderings the prose later cites as magnitudes.
        ValueManifest m = ValueManifest.capture(
                "max_divisor_count ≤ -2.0000 · mertens_per_sqrt_W + 166.6706",
                List.of(verify("worst margin: +120.97163 at window starting 1441000. "
                        + "lhs=+288.00000 rhs_scalar=-0.17878 bound=+167.02837")));

        assertThat(m.entryCount()).isGreaterThanOrEqualTo(7);
        // Hypothesis constants present with their literals.
        assertThat(m.entries()).anyMatch(e ->
                e.kind() == ManifestEntry.Kind.HYPOTHESIS_CONSTANT
                        && e.rendering().equals("166.6706"));
        // Tool values keep the exact signed rendering and the producing call.
        assertThat(m.entries()).anyMatch(e ->
                e.kind() == ManifestEntry.Kind.TOOL_OUTPUT
                        && e.rendering().equals("+120.97163")
                        && e.producedBy().equals("call_1"));
        assertThat(m.truncatedCalls()).isEmpty();
    }

    @Test
    void perCallCapIsRecordedNotSilent() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < ValueManifest.MAX_ENTRIES_PER_CALL + 50; i++) {
            big.append(" v=").append(i).append(".125");   // distinct renderings
        }
        ValueManifest m = ValueManifest.capture(null, List.of(verify(big.toString())));
        assertThat(m.entryCount()).isEqualTo(ValueManifest.MAX_ENTRIES_PER_CALL);
        assertThat(m.truncatedCalls()).containsExactly("call_1");
    }

    @Test
    void nullInputsYieldEmptyManifest() {
        ValueManifest m = ValueManifest.capture(null, null);
        assertThat(m.entryCount()).isZero();
        assertThat(m.callCount()).isZero();
        assertThat(m.resolve(42.0)).isEmpty();
    }

    // ── Resolve: direct, magnitude, derived ────────────────────────────────

    @Test
    void resolvesDirectAndMagnitudeCitations() {
        ValueManifest m = ValueManifest.capture(null,
                List.of(verify("deviation -15.286 across the sweep")));
        // Signed source, prose cites the magnitude (ladder Phase 16 behavior).
        assertThat(m.resolve(15.286)).isPresent();
        assertThat(m.resolve(-15.286)).isPresent();
        assertThat(m.resolve(15.286).get().description()).startsWith("m1_");
    }

    @Test
    void resolvesHypothesisCoefficientTimesToolValue() {
        // 2·0.077 = 0.154 — the k·x derivation the ladder recognizes.
        ValueManifest m = ValueManifest.capture(
                "lhs ≤ 2.0000 · rhs + 1.14",
                List.of(verify("lhs=+0.07700 holds")));
        Optional<ValueManifest.Resolution> r = m.resolve(0.154);
        assertThat(r).isPresent();
        assertThat(r.get().description()).startsWith("derived:");
    }

    @Test
    void resolvesSameCallSumAndDifference() {
        // P-b72c-219's 0.019 shape — but within ONE call it resolves;
        // the manifest stays same-call-strict like the ladder.
        ValueManifest m = ValueManifest.capture(null,
                List.of(verify("fraction = 0.044 baseline 0.025")));
        assertThat(m.resolve(0.019)).isPresent();   // 0.044 - 0.025, same call
        assertThat(m.resolve(0.069)).isPresent();   // 0.044 + 0.025
    }

    @Test
    void p229_hypothesisMinusToolValue_resolves() {
        // First shadow-session finding (P-b72c-229): "a post-hoc deviation of
        // 0.147 from the predicted value" = preregistered 0.180 − observed
        // 0.033 [call_4]. The prediction-vs-observation gap must resolve.
        ValueManifest m = ValueManifest.capture(
                "joint density is near 0.180 in [300, 750)",
                List.of(verify("fraction satisfying predicate = 0.033 ± 0.017")));
        Optional<ValueManifest.Resolution> r = m.resolve(0.147);
        assertThat(r).isPresent();
        assertThat(r.get().description()).startsWith("derived:|0.18-0.033|@hyp");
    }

    @Test
    void crossCallArithmeticDoesNotResolve() {
        // The same two numbers split across calls: stays unresolvable —
        // matching the ladder's conservative same-call rule.
        ValueManifest m = ValueManifest.capture(null, List.of(
                verify("fraction = 0.044"),
                new ToolInvocation("run_predicate_scan", "{}",
                        "fraction = 0.025", 1, "call_2")));
        assertThat(m.resolve(0.019)).isEmpty();
    }

    @Test
    void untraceableFabricationStaysUnresolved() {
        // P-b72c-217's '70' framing value: nothing in trace or hypothesis.
        ValueManifest m = ValueManifest.capture(
                "max_prime_gap ≤ -2.0000 · abundant_minus_deficient + 35.0400",
                List.of(verify("lhs=+0.07700 rhs_scalar=+0.07700 holds")));
        assertThat(m.resolve(70.0)).isEmpty();
    }

    // ── JSON sidecar ────────────────────────────────────────────────────────

    @Test
    void jsonColumnarShapeIsStable() {
        // Columnar after P-b72c-230's 705-entry manifest hit 70KB in
        // per-entry-object form; kind is T (tool) / H (hypothesis).
        ValueManifest m = ValueManifest.capture("k = 2.0",
                List.of(verify("margin -0.01261")));
        String json = m.toJson();
        assertThat(json)
                .startsWith("{\"cols\":[\"label\",\"value\",\"rendering\",\"producedBy\",\"kind\"],"
                        + "\"rows\":[")
                .contains("[\"m1_1\",-0.01261,\"-0.01261\",\"call_1\",\"T\"]")
                .contains("[\"h_1\",2.0,\"2.0\",\"hypothesis\",\"H\"]")
                .endsWith("\"truncatedCalls\":[]}");
    }
}
