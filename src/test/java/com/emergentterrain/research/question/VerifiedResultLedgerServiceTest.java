package com.emergentterrain.research.question;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 C1 — deterministic trace→results extraction. */
class VerifiedResultLedgerServiceTest {

    private static ToolInvocation call(String fn, String result) {
        return new ToolInvocation(fn, "{}", result, 0);
    }

    private static ToolInvocation verification(double k, double c, long start, long size, int windows,
                                               String result) {
        String args = "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\","
                + "\"k\":" + k + ",\"c\":" + c + ",\"window_start\":" + start
                + ",\"window_size\":" + size + ",\"num_windows\":" + windows + "}";
        return new ToolInvocation("verify_conjecture", args, result, 0);
    }

    @Test
    void extractsLadderConstantAndTightBound_p783Shapes() {
        List<ToolInvocation> trace = List.of(
                call("verify_growth", "verify_growth scale-ladder: max_prime_gap in wigert coords "
                        + "measured across 5 scales [100000→0.86925, 1000000000→0.70124]. Growth "
                        + "constant 0.69315. Per-window: junk"),
                call("verify_constant", "verify_constant: mean_divisor_excess ~ 1.15436 over 20 "
                        + "window(s). CONVERGED to target 1.15440: mean rel-dev 0.0000."
                        + "\ndefinitions (exact instrument formulas): x = y"),
                verification(0.6490, 0.0000, 1_500_000L, 10_000L, 10,
                        "verify_conjecture: max_prime_gap <= +0.6490 * "
                        + "log_sq_window_start + +0.0000 HELD across 10 window(s) of size 10000 "
                        + "starting at 1500000. worst margin: -0.03547 at window starting 1500000. relative slack: 0.0003 "
                        + "(smaller = tighter). verdict legend: margin = lhs - bound"),
                verification(0.6480, 0.0000, 1_500_000L, 10_000L, 10,
                        "verify_conjecture: max_prime_gap <= +0.6480 * log_sq_window_start + +0.0000 "
                                + "REFUTED across 10 window(s) of size 10000 starting at 1500000. "
                                + "worst margin: +0.03547"));
        List<VerifiedResult> out =
                VerifiedResultLedgerService.extract(trace, "P-1", "max_prime_gap");
        assertThat(out).hasSize(3);
        assertThat(out.get(0).kind()).isEqualTo("LADDER");
        assertThat(out.get(0).statement()).contains("[100000→0.86925").doesNotContain("junk");
        assertThat(out.get(1).kind()).isEqualTo("CONSTANT");
        assertThat(out.get(1).statement()).doesNotContain("definitions (");
        assertThat(out.get(2).kind()).isEqualTo("TIGHT_BOUND");
        assertThat(out.get(2).statement())
                .contains("0.6490").contains("relative slack: 0.0003")
                .contains("nearby same-grid refutation present")
                .doesNotContain("verdict legend");
    }

    @Test
    void unpairedTightHold_cannotEnterTheLedger() {
        ToolInvocation held = verification(0.6490, 0.0000, 1_500_000L, 10_000L, 10,
                "verify_conjecture: max_prime_gap <= +0.6490 * log_sq_window_start + +0.0000 "
                        + "HELD across 10 window(s) of size 10000 starting at 1500000. "
                        + "worst margin: -0.03547 at window starting 1500000. relative slack: 0.0003");

        assertThat(VerifiedResultLedgerService.extract(List.of(held), "P-unpaired", "max_prime_gap"))
                .isEmpty();
    }

    @Test
    void refutationOnAnotherGrid_cannotAuthorizeATightBound() {
        ToolInvocation held = verification(0.6490, 0.0000, 1_500_000L, 10_000L, 10,
                "verify_conjecture: max_prime_gap <= +0.6490 * log_sq_window_start + +0.0000 "
                        + "HELD across 10 window(s) of size 10000 starting at 1500000. "
                        + "worst margin: -0.03547 at window starting 1500000. relative slack: 0.0003");
        ToolInvocation wrongGridRefute = verification(0.6480, 0.0000, 1_600_000L, 10_000L, 10,
                "verify_conjecture: max_prime_gap <= +0.6480 * log_sq_window_start + +0.0000 "
                        + "REFUTED across 10 window(s) of size 10000 starting at 1600000. "
                        + "worst margin: +0.03547");

        assertThat(VerifiedResultLedgerService.extract(
                List.of(held, wrongGridRefute), "P-wrong-grid", "max_prime_gap")).isEmpty();
    }

    @Test
    void looseHoldsAndUnsettledConstants_areNotRecorded() {
        List<ToolInvocation> trace = List.of(
                call("verify_conjecture", "verify_conjecture: a <= b HELD across 10 window(s). "
                        + "relative slack: 0.5725."),
                call("verify_constant", "verify_constant: cramer ~ 0.37 over 20 window(s). "
                        + "no target — spread 0.7149 (not yet settled)."));
        // The unsettled verify_constant DOES carry "estimated limit"? No — this one doesn't.
        assertThat(VerifiedResultLedgerService.extract(trace, "P-1", "f")).isEmpty();
        assertThat(VerifiedResultLedgerService.extract(null, "P-1", "f")).isEmpty();
    }
}
