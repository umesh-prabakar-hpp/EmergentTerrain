package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AffineBindingIndependentGridValidatorTest {

    @Test
    void tightSameGridPairNeedsTheLockedIndependentHold() {
        List<ToolInvocation> localPair = List.of(
                verify(args(900_000_000L, 0.0), tightHeld()),
                verify(args(900_000_000L, -0.32185), refuted()));

        assertThat(AffineBindingIndependentGridValidator
                .tightBindingWithoutIndependentReplication(localPair)).isTrue();
    }

    @Test
    void lockedIndependentHoldAndSensitivityRefutationClearTheGate() {
        List<ToolInvocation> trace = List.of(
                verify(args(900_000_000L, 0.0), tightHeld()),
                verify(args(900_000_000L, -0.32185), refuted()),
                verify(args(1_350_000_000L, 0.0),
                        "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + +0.0000 "
                                + "HELD across 10 window(s) of size 10000 starting at 1350000000. "
                                + "worst margin: -0.10000 at window starting 1350010000. "
                                + "relative slack: 0.0005"),
                verify(args(1_350_000_000L, -0.32185),
                        "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + -0.3219 "
                                + "REFUTED across 10 window(s) of size 10000 starting at 1350000000. "
                                + "worst margin: +0.08000 at window starting 1350010000."));

        assertThat(AffineBindingIndependentGridValidator
                .tightBindingWithoutIndependentReplication(trace)).isFalse();
    }

    @Test
    void lockedIndependentHoldWithoutSensitivityRefutationStillFailsTheGate() {
        List<ToolInvocation> trace = List.of(
                verify(args(900_000_000L, 0.0), tightHeld()),
                verify(args(900_000_000L, -0.32185), refuted()),
                verify(args(1_350_000_000L, 0.0),
                        "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + +0.0000 "
                                + "HELD across 10 window(s) of size 10000 starting at 1350000000. "
                                + "worst margin: -0.10000 at window starting 1350010000. "
                                + "relative slack: 0.0005"));

        assertThat(AffineBindingIndependentGridValidator
                .tightBindingWithoutIndependentReplication(trace)).isTrue();
    }

    @Test
    void arbitrarySecondGridDoesNotMasqueradeAsTheLockedReplication() {
        List<ToolInvocation> trace = List.of(
                verify(args(900_000_000L, 0.0), tightHeld()),
                verify(args(900_000_000L, -0.32185), refuted()),
                verify(args(700_000_000L, 0.0),
                        "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + +0.0000 "
                                + "HELD across 10 window(s) of size 10000 starting at 700000000. "
                                + "worst margin: -0.10000 at window starting 700010000. "
                                + "relative slack: 0.0005"));

        assertThat(AffineBindingIndependentGridValidator
                .tightBindingWithoutIndependentReplication(trace)).isTrue();
    }

    private static ToolInvocation verify(String args, String result) {
        return new ToolInvocation("verify_conjecture", args, result, 0);
    }

    private static String args(long start, double c) {
        return "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\","
                + "\"k\":0.424,\"c\":" + c + ",\"window_start\":" + start
                + ",\"window_size\":10000,\"num_windows\":10}";
    }

    private static String tightHeld() {
        return "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + +0.0000 "
                + "HELD across 10 window(s) of size 10000 starting at 900000000. "
                + "worst margin: -0.24185 at window starting 900010000. relative slack: 0.0013 "
                + "TIGHT HOLD — re-verify with c=-0.32185.";
    }

    private static String refuted() {
        return "verify_conjecture: max_prime_gap <= +0.4240 * log_sq_window_start + -0.3219 "
                + "REFUTED across 10 window(s) of size 10000 starting at 900000000. "
                + "worst margin: +0.08000 at window starting 900010000.";
    }
}
