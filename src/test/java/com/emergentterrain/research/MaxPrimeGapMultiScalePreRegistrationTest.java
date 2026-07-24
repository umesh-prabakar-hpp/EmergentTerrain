package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaxPrimeGapMultiScalePreRegistrationTest {

    private static final PreRegistration REGISTRATION = MaxPrimeGapMultiScalePreRegistration.from(
            new Conjecture("MATHEMATICS", "max_prime_gap", "prime_density", 0.424, 0.0));

    @Test
    void completeProtocolLocksOneFitAcrossLocationOffsetAndWidthHoldouts() {
        List<ToolInvocation> trace = List.of(
                held(100_000_000L, 10_000L, 10, 0.424, 0.0, 0.04),
                refuted(100_000_000L, 10_000L, 10, 0.424, -0.32185),
                held(150_000_000L, 10_000L, 10, 0.424, 0.0, 0.01),
                held(900_000_000L, 10_000L, 10, 0.424, 0.0, 0.02),
                held(900_005_000L, 10_000L, 10, 0.424, 0.0, 0.03),
                held(900_000_000L, 20_000L, 10, 0.424, 0.0, 0.04));

        assertThat(MaxPrimeGapMultiScalePreRegistration.isLocked(REGISTRATION)).isTrue();
        assertThat(MaxPrimeGapMultiScalePreRegistration.hasExactEvidence(REGISTRATION, trace)).isTrue();
        assertThat(MaxPrimeGapMultiScalePreRegistration.assess(REGISTRATION, trace).complete()).isTrue();
    }

    @Test
    void refittingTheFarHoldoutCannotMasqueradeAsASharedEnvelope() {
        List<ToolInvocation> trace = List.of(
                held(100_000_000L, 10_000L, 10, 0.424, 0.0, 0.04),
                refuted(100_000_000L, 10_000L, 10, 0.424, -0.32185),
                held(150_000_000L, 10_000L, 10, 0.424, 0.0, 0.01),
                held(900_000_000L, 10_000L, 10, 0.2541, 0.0, 0.04),
                held(900_005_000L, 10_000L, 10, 0.424, 0.0, 0.03),
                held(900_000_000L, 20_000L, 10, 0.424, 0.0, 0.04));

        MaxPrimeGapMultiScalePreRegistration.Assessment assessment =
                MaxPrimeGapMultiScalePreRegistration.assess(REGISTRATION, trace);

        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.missingHoldouts()).extracting(MaxPrimeGapMultiScalePreRegistration.Grid::label)
                .containsExactly("far_holdout");
        assertThat(MaxPrimeGapMultiScalePreRegistration.completionDirective(REGISTRATION, trace))
                .contains("k=0.424").contains("far_holdout").doesNotContain("k=0.2541");
    }

    @Test
    void looseOrMissingRelativeSlackDoesNotSatisfyTheProtocol() {
        List<ToolInvocation> trace = List.of(
                held(100_000_000L, 10_000L, 10, 0.424, 0.0, 0.06),
                refuted(100_000_000L, 10_000L, 10, 0.424, -0.32185));

        assertThat(MaxPrimeGapMultiScalePreRegistration.hasExactEvidence(REGISTRATION, trace)).isFalse();
        assertThat(MaxPrimeGapMultiScalePreRegistration.completionDirective(REGISTRATION, trace))
                .contains("fixed fit grid");
    }

    @Test
    void holdoutsRecordedBeforeTheFitRefutationDoNotSatisfyTheProtocol() {
        List<ToolInvocation> trace = List.of(
                held(100_000_000L, 10_000L, 10, 0.424, 0.0, 0.04),
                held(150_000_000L, 10_000L, 10, 0.424, 0.0, 0.01),
                held(900_000_000L, 10_000L, 10, 0.424, 0.0, 0.02),
                held(900_005_000L, 10_000L, 10, 0.424, 0.0, 0.03),
                held(900_000_000L, 20_000L, 10, 0.424, 0.0, 0.04),
                refuted(100_000_000L, 10_000L, 10, 0.424, -0.32185));

        assertThat(MaxPrimeGapMultiScalePreRegistration.hasExactEvidence(REGISTRATION, trace)).isFalse();
        assertThat(MaxPrimeGapMultiScalePreRegistration.assess(REGISTRATION, trace).missingHoldouts())
                .hasSize(4);
    }

    @Test
    void appliesOnlyToMaxPrimeGap() {
        assertThat(MaxPrimeGapMultiScalePreRegistration.from(
                new Conjecture("MATHEMATICS", "mean_divisor_excess", "log_window_start", 1.0, 0.0)))
                .isNull();
    }

    private static ToolInvocation held(long start, long size, int windows, double k, double c, double slack) {
        return new ToolInvocation("verify_conjecture", args(start, size, windows, k, c),
                "verify_conjecture: max_prime_gap <= +" + k + " * log_sq_window_start + +" + c
                        + " HELD across " + windows + " window(s) of size " + size + " starting at " + start
                        + ". worst margin: -0.1000 at window starting " + start + ". relative slack: " + slack,
                0);
    }

    private static ToolInvocation refuted(long start, long size, int windows, double k, double c) {
        return new ToolInvocation("verify_conjecture", args(start, size, windows, k, c),
                "verify_conjecture: max_prime_gap <= +" + k + " * log_sq_window_start + " + c
                        + " REFUTED across " + windows + " window(s) of size " + size + " starting at " + start + ".",
                0);
    }

    private static String args(long start, long size, int windows, double k, double c) {
        return "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":" + k
                + ",\"c\":" + c + ",\"window_start\":" + start + ",\"window_size\":" + size
                + ",\"num_windows\":" + windows + "}";
    }
}
