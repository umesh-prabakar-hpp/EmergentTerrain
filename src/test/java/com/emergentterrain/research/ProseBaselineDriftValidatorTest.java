package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-21 review §1 rec #4 follow-up — unit tests for {@link
 * ProseBaselineDriftValidator}. The canonical corpus signature is
 * P-a328-871: prose says "close to the pre-registered baseline value of
 * 0.300" while {@code run_predicate_scan} was invoked with {@code baseline=0}.
 */
class ProseBaselineDriftValidatorTest {

    private static ToolInvocation predicateScan(double baseline) {
        return new ToolInvocation(
                "run_predicate_scan",
                "{\"baseline\":" + baseline + ",\"metric\":\"mean_of:sigmaSum\","
                        + "\"predicate\":\"isAbundant\",\"range_start\":45,\"range_end\":65}",
                "Mean of sigmaSum = 79 ± 10",
                0,
                "call_1");
    }

    @Test
    void p871SignatureFlags() {
        // Prose: 0.300 baseline. Tool args: baseline=0. → flag.
        List<String> reasons = ProseBaselineDriftValidator.validate(
                "Approximately 30% of the integers are abundant or perfect, and their "
                        + "mean divisor-sum σ(n) would be close to the pre-registered "
                        + "baseline value of 0.300.",
                List.of(predicateScan(0.0)));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("baseline=0.3")
                .contains("[0]");
    }

    @Test
    void proseMatchesArgsExactlyPasses() {
        List<String> reasons = ProseBaselineDriftValidator.validate(
                "We expect a baseline of 79 over the range.",
                List.of(predicateScan(79.0)));
        assertThat(reasons).isEmpty();
    }

    @Test
    void proseWithinOnePercentOfArgsPasses() {
        // 79.5 vs 79.0 → 0.5/79.5 ≈ 0.0063, under 1% relative. → no flag.
        List<String> reasons = ProseBaselineDriftValidator.validate(
                "Baseline of 79.5 anticipated.",
                List.of(predicateScan(79.0)));
        assertThat(reasons).isEmpty();
    }

    @Test
    void multiplePredicateScansAnyMatchPasses() {
        // Two scans, two baselines. Prose mentions one; the other doesn't matter.
        List<String> reasons = ProseBaselineDriftValidator.validate(
                "Baseline of 50 expected.",
                List.of(predicateScan(0.0), predicateScan(50.0)));
        assertThat(reasons).isEmpty();
    }

    @Test
    void multipleProseBaselinesAllChecked() {
        // Two prose baselines, only one matches a trace value. → flag the other.
        List<String> reasons = ProseBaselineDriftValidator.validate(
                "First baseline = 79 and a secondary baseline of 0.300 against the "
                        + "rest of the window.",
                List.of(predicateScan(79.0)));
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("baseline=0.3");
    }

    @Test
    void noPredicateScanInTraceReturnsEmpty() {
        // Trace has only run_sandboxed_code; no baselines to compare against.
        ToolInvocation sandbox = new ToolInvocation(
                "run_sandboxed_code", "{}", "result=42", 0, "call_1");
        assertThat(ProseBaselineDriftValidator.validate(
                "Baseline of 0.300 expected.", List.of(sandbox)))
                .isEmpty();
    }

    @Test
    void noBaselineInProseReturnsEmpty() {
        // Hypothesis with no "baseline" mention. → no flag regardless of args.
        assertThat(ProseBaselineDriftValidator.validate(
                "Twin prime density approximates 0.300 in this window.",
                List.of(predicateScan(0.0))))
                .isEmpty();
    }

    @Test
    void percentageSuffixSkipped() {
        // "baseline of 30%" — the percent sign rules this out as a probability;
        // skipping it avoids over-flagging. 30 is not extracted.
        assertThat(ProseBaselineDriftValidator.validate(
                "Baseline of 30% across the survey.",
                List.of(predicateScan(0.0))))
                .isEmpty();
    }

    @Test
    void nullInputsReturnEmpty() {
        assertThat(ProseBaselineDriftValidator.validate(null, List.of(predicateScan(0.0))))
                .isEmpty();
        assertThat(ProseBaselineDriftValidator.validate("Baseline of 0.300.", null))
                .isEmpty();
        assertThat(ProseBaselineDriftValidator.validate("Baseline of 0.300.", List.of()))
                .isEmpty();
    }

    @Test
    void approximatelyAndCloseToVariantsMatch() {
        // Various phrasings of "baseline approximately X", "baseline ≈ X" all parse.
        assertThat(ProseBaselineDriftValidator.validate(
                "Baseline ≈ 0.300 anticipated.", List.of(predicateScan(0.0))))
                .hasSize(1);
        assertThat(ProseBaselineDriftValidator.validate(
                "Baseline approximately 0.300 anticipated.", List.of(predicateScan(0.0))))
                .hasSize(1);
        assertThat(ProseBaselineDriftValidator.validate(
                "Baseline close to 0.300 anticipated.", List.of(predicateScan(0.0))))
                .hasSize(1);
    }
}
