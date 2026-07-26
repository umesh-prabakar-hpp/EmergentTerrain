package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaxPrimeGapProtocolStateExecutorTest {
    private static final PreRegistration REGISTRATION = MaxPrimeGapMultiScalePreRegistration.from(
            new Conjecture("MATHEMATICS", "max_prime_gap", "prime_density", 0.424, 0.0));

    @Test
    void carriesVerifierSuggestedKIntoTheFixedFitGridExactlyOnce() {
        List<ToolInvocation> trace = new ArrayList<>(List.of(looseFit()));

        MaxPrimeGapProtocolStateExecutor.PlannedCall action =
                MaxPrimeGapProtocolStateExecutor.next(REGISTRATION, trace).orElseThrow();

        assertThat(action.phase()).isEqualTo("tight_fit");
        assertThat(action.argumentsJson()).contains("\"k\":0.496").contains("\"c\":0.0")
                .contains("\"window_start\":100000000").contains("\"window_size\":10000")
                .contains("\"num_windows\":10");

        trace.add(new ToolInvocation("verify_conjecture", action.argumentsJson(),
                "verify_conjecture: max_prime_gap <= +0.4960 * log_sq_window_start + +0.0000 "
                        + "REFUTED across 10 window(s).", 0));
        assertThat(MaxPrimeGapProtocolStateExecutor.next(REGISTRATION, trace)).isEmpty();
    }

    @Test
    void carriesVerifierNamedRefutationThenEveryRegisteredHoldoutWithoutRefitting() {
        List<ToolInvocation> trace = new ArrayList<>(List.of(
                tightFit(), refutedFit(), held(150_000_000L, 10_000L, 10),
                held(900_000_000L, 10_000L, 10), held(900_005_000L, 10_000L, 10)));

        MaxPrimeGapProtocolStateExecutor.PlannedCall width =
                MaxPrimeGapProtocolStateExecutor.next(REGISTRATION, trace).orElseThrow();
        assertThat(width.phase()).isEqualTo("width_holdout");
        assertThat(width.argumentsJson()).contains("\"k\":0.496").contains("\"c\":0.0")
                .contains("\"window_start\":900000000").contains("\"window_size\":20000")
                .contains("\"num_windows\":10");

        trace.add(new ToolInvocation("verify_conjecture", width.argumentsJson(), heldResult(), 0));
        assertThat(MaxPrimeGapMultiScalePreRegistration.hasExactEvidence(REGISTRATION, trace)).isTrue();
        assertThat(MaxPrimeGapProtocolStateExecutor.next(REGISTRATION, trace)).isEmpty();
    }

    @Test
    void emitsNamedRefutationBeforeHoldouts() {
        MaxPrimeGapProtocolStateExecutor.PlannedCall action = MaxPrimeGapProtocolStateExecutor.next(
                REGISTRATION, List.of(tightFit())).orElseThrow();

        assertThat(action.phase()).isEqualTo("fit_refutation");
        assertThat(action.argumentsJson()).contains("\"k\":0.496").contains("\"c\":-0.32185")
                .contains("\"window_start\":100000000");
    }

    private static ToolInvocation looseFit() {
        return new ToolInvocation("verify_conjecture", args(1.0, 0.0, 100_000_000L, 10_000L, 10),
                "verify_conjecture: max_prime_gap <= +1.0000 * log_sq_window_start + +0.0000 HELD across "
                        + "10 window(s). relative slack: 0.5049. LOOSE: the tightest k that holds these "
                        + "windows is ~0.4951 — re-verify at k=0.4960 (keep c=+0.0000) for a TIGHT bound.", 0);
    }

    private static ToolInvocation tightFit() {
        return new ToolInvocation("verify_conjecture", args(0.496, 0.0, 100_000_000L, 10_000L, 10),
                "verify_conjecture: max_prime_gap <= +0.4960 * log_sq_window_start + +0.0000 HELD across "
                        + "10 window(s). relative slack: 0.0110. TIGHT HOLD — re-verify with c=-0.32185.", 0);
    }

    private static ToolInvocation refutedFit() {
        return new ToolInvocation("verify_conjecture", args(0.496, -0.32185, 100_000_000L, 10_000L, 10),
                "verify_conjecture: max_prime_gap <= +0.4960 * log_sq_window_start + -0.3219 REFUTED across "
                        + "10 window(s).", 0);
    }

    private static ToolInvocation held(long start, long size, int windows) {
        return new ToolInvocation("verify_conjecture", args(0.496, 0.0, start, size, windows), heldResult(), 0);
    }

    private static String heldResult() {
        return "verify_conjecture: max_prime_gap <= +0.4960 * log_sq_window_start + +0.0000 HELD across "
                + "10 window(s). relative slack: 0.0110.";
    }

    private static String args(double k, double c, long start, long size, int windows) {
        return "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":" + k
                + ",\"c\":" + c + ",\"window_start\":" + start + ",\"window_size\":" + size
                + ",\"num_windows\":" + windows + "}";
    }
}
