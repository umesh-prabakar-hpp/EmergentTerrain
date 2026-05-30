package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 F3 — unit tests for {@link SinglePointSweepValidator}. The
 * three named refresh-#2 occurrences (P-507 "sixteen sampled cases" + 1
 * call, P-512 "16 measured Ea values" + 2 calls, P-494 "sweep over
 * [591,650]" + 1 call) are the corpus signatures each test mirrors.
 */
class SinglePointSweepValidatorTest {

    @Test
    void wordCountClaimAgainstOneTraceCallFlags() {
        // P-507 signature: "sixteen sampled cases" backed by 1 call.
        // 16 > 2·1 = 2 → flag.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{\"x\":1}", 0));
        List<String> reasons = SinglePointSweepValidator.validate(
                "The Arrhenius multiplier was averaged over sixteen sampled cases.",
                trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("[16]")
                .contains("trace records only 1");
    }

    @Test
    void digitCountClaimAgainstTwoTraceCallsFlags() {
        // P-512 signature: "16 measured Ea values" backed by 2 calls.
        // 16 > 2·2 = 4 → flag.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{\"x\":1.142}", 0),
                new ToolInvocation("run_param_sweep", "{}", "{\"x\":1.052}", 1));
        List<String> reasons = SinglePointSweepValidator.validate(
                "We collected 16 measured Ea values across the activation range.",
                trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("[16]").contains("trace records only 2");
    }

    @Test
    void nEqualsClaimAgainstFiveTraceCallsFlags() {
        // n=40 windows against 5 calls: 40 > 2·5 = 10 → flag.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_predicate_scan", "{}", "{\"h\":1}", 0),
                new ToolInvocation("run_predicate_scan", "{}", "{\"h\":2}", 1),
                new ToolInvocation("run_predicate_scan", "{}", "{\"h\":3}", 2),
                new ToolInvocation("run_predicate_scan", "{}", "{\"h\":4}", 3),
                new ToolInvocation("run_predicate_scan", "{}", "{\"h\":5}", 4));
        List<String> reasons = SinglePointSweepValidator.validate(
                "The anomaly fraction was 0.08 (n=40) across the survey.",
                trace);
        assertThat(reasons).hasSize(1);
    }

    @Test
    void smallClaimWithinSlackPasses() {
        // "4 measured cases" + 2 calls: 4 ≤ 2·2 = 4 → pass (within slack;
        // legitimate within-call sweep with internal sampling).
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{}", 0),
                new ToolInvocation("run_param_sweep", "{}", "{}", 1));
        List<String> reasons = SinglePointSweepValidator.validate(
                "The 4 measured cases agreed within tolerance.", trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void emptyTraceShortCircuitsToEmpty() {
        // Legacy / Ollama-fallback / JSON-only papers legitimately have no
        // trace. Validator returns empty so they don't false-positive.
        List<String> reasons = SinglePointSweepValidator.validate(
                "Across sixteen sampled cases, the result held steady.",
                List.of());
        assertThat(reasons).isEmpty();
    }

    @Test
    void nullTraceShortCircuitsToEmpty() {
        List<String> reasons = SinglePointSweepValidator.validate(
                "Across sixteen sampled cases, the result held steady.",
                null);
        assertThat(reasons).isEmpty();
    }

    @Test
    void blankFindingsReturnsEmpty() {
        assertThat(SinglePointSweepValidator.validate("", List.of(
                new ToolInvocation("run_param_sweep", "{}", "{}", 0))))
                .isEmpty();
    }

    @Test
    void unrelatedWordNumberInProseDoesNotFlag() {
        // "four reviewers" / "two authors" — common prose that mentions a
        // small count but not in a sample-size context. Validator's word
        // patterns are anchored to sample/case/window/etc. nouns, so the
        // count is not picked up.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{}", 0));
        List<String> reasons = SinglePointSweepValidator.validate(
                "We had four reviewers and two authors.", trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void multipleOverclaimsFoldIntoOneReason() {
        // Two distinct claims both exceed slack: each captured, but only
        // one reason emitted (compact call-path stamp).
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{}", 0));
        List<String> reasons = SinglePointSweepValidator.validate(
                "We tested 40 windows and twenty samples.", trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("40").contains("20");
    }

    // ---- REVIEW_REPORT b72c4e10 §1 rec #1: yield-aware allowance ----
    // The claimed sample count must be checked against the data points the
    // trace actually produced, not just the call count. These three mirror
    // the false-fired corpus papers and must now PASS.

    @Test
    void windowClaimBackedByVerifyConjectureYieldPasses() {
        // P-b72c-16: Findings cite "15 windows"; one verify_conjecture call
        // genuinely swept 21 windows. 15 <= max(2*5, 21) -> pass.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("verify_conjecture", "{\"num_windows\":20}",
                        "verify_conjecture: REFUTED across 21 window(s) of size 2000 "
                                + "starting at 750000. worst margin: +0.847", 0),
                new ToolInvocation("verify_conjecture", "{}", "Invalid: exceeds MAX_N", 1),
                new ToolInvocation("search_literature", "{}", "no references found", 2),
                new ToolInvocation("verify_conjecture", "{\"num_windows\":15}",
                        "verify_conjecture: REFUTED across 15 window(s) of size 500", 3),
                new ToolInvocation("verify_conjecture", "{\"num_windows\":15}",
                        "verify_conjecture: REFUTED across 16 window(s) of size 3000", 4));
        List<String> reasons = SinglePointSweepValidator.validate(
                "Call [call_4] swept 15 windows of size 500 beginning at start=2000.",
                trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void predicateScanNYieldBacksNClaimPasses() {
        // P-b72c-19: Findings cite n=400; a run_predicate_scan over [300,700)
        // evaluated n=400. 400 <= max(2*5, 400) -> pass.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_predicate_scan", "{\"range_start\":300,\"range_end\":700}",
                        "Predicate scan: fraction = 0.033 +/- 0.017 (95% CI, n=400)", 0),
                new ToolInvocation("run_predicate_scan", "{}",
                        "Predicate scan: fraction = 0.16 (95% CI, n=400)", 1),
                new ToolInvocation("run_predicate_scan", "{}",
                        "Predicate scan: fraction = 0.14 (95% CI, n=400)", 2),
                new ToolInvocation("search_literature", "{}", "no references found", 3),
                new ToolInvocation("run_sandboxed_code", "{}",
                        "Sandboxed computation result=-0.523 iters_used=0 recorded n=7", 4));
        List<String> reasons = SinglePointSweepValidator.validate(
                "The joint density was 0.033 (n=400) across the survey.", trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void sandboxItersYieldBacksLargerClaimPasses() {
        // A run_sandboxed_code loop reporting iters_used=4999 backs a sizeable
        // sweep claim that the 2*calls floor alone would reject.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_sandboxed_code", "{}",
                        "Sandboxed computation over [1,5000): result=40 iters_used=4999", 0));
        List<String> reasons = SinglePointSweepValidator.validate(
                "We found 40 windows in violation across the dense scan.", trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void fabricatedClaimWithNoSweepYieldStillFlags() {
        // Regression guard: a large claim against a trace that produced NO
        // sweep output (scalar results only) is real fabrication -> still flag.
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("compute_value", "{}", "{\"result\":1.07}", 0),
                new ToolInvocation("search_literature", "{}", "no references found", 1));
        List<String> reasons = SinglePointSweepValidator.validate(
                "The estimate averaged over n=400 sampled cases.", trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("[400]");
    }

    @Test
    void structuredFindingsMapOverloadConcatenatesBlocks() {
        // Map overload joins blocks before scanning so a claim split
        // across blocks still fires.
        java.util.Map<Integer, String> blocks = java.util.Map.of(
                1, "Block one summary.",
                2, "Block two reports n=40 across runs.");
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{}", 0));
        List<String> reasons = SinglePointSweepValidator.validate(blocks, trace);
        assertThat(reasons).hasSize(1);
    }
}
