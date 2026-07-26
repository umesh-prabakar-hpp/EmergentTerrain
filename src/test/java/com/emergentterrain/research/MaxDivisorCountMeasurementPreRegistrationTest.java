package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaxDivisorCountMeasurementPreRegistrationTest {

    private static final PreRegistration REGISTRATION = MaxDivisorCountMeasurementPreRegistration.from(
            new Conjecture("MATHEMATICS", "max_divisor_count", "wigert_bound", 0.928, 0.0));

    @Test
    void completeProtocolLocksOneFitAcrossFixedScaleAndWindowHoldouts() {
        List<ToolInvocation> trace = List.of(
                growthLadder(),
                held(1_000_000_000L, 5_000L, 20, 0.928, 0.0, 0.04),
                refuted(1_000_000_000L, 5_000L, 20, 0.928, -0.29664),
                held(1_500_000_000L, 5_000L, 20, 0.928, 0.0, 0.02),
                held(1_000_005_000L, 5_000L, 20, 0.928, 0.0, 0.03),
                held(1_000_000_000L, 10_000L, 10, 0.928, 0.0, 0.04));

        assertThat(MaxDivisorCountMeasurementPreRegistration.isLocked(REGISTRATION)).isTrue();
        assertThat(MaxDivisorCountMeasurementPreRegistration.hasExactEvidence(REGISTRATION, trace)).isTrue();
        assertThat(MaxDivisorCountMeasurementPreRegistration.assess(REGISTRATION, trace).complete()).isTrue();
    }

    @Test
    void refittingOrACommonLooseEnvelopeCannotSatisfyTheRegisteredHoldouts() {
        List<ToolInvocation> trace = List.of(
                held(1_000_000_000L, 5_000L, 20, 0.928, 0.0, 0.04),
                refuted(1_000_000_000L, 5_000L, 20, 0.928, -0.29664),
                held(1_500_000_000L, 5_000L, 20, 0.900, 0.0, 0.04),
                held(1_000_005_000L, 5_000L, 20, 0.928, 0.0, 0.06),
                held(1_000_000_000L, 10_000L, 10, 0.928, 0.0, 0.04));

        assertThat(MaxDivisorCountMeasurementPreRegistration.hasExactEvidence(REGISTRATION, trace)).isFalse();
        assertThat(MaxDivisorCountMeasurementPreRegistration.assess(REGISTRATION, trace).missingHoldouts())
                .extracting(MaxDivisorCountMeasurementPreRegistration.Grid::label)
                .containsExactly("location_holdout", "offset_holdout");
    }

    @Test
    void wigertReferenceMustBeSearchedReadAndCitedWithTheRequiredAnchors() {
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("search_literature", searchArgs("divisor", "wigert"),
                        "EVIDENCE [HF123] Wigert's divisor theorem", 0),
                new ToolInvocation("read_reference", "{\"key\":\"HF123\",\"query\":\"Wigert divisor\"}",
                        "read_reference HF123 — Top passages matching Wigert divisor: ...", 0));

        assertThat(MaxDivisorCountMeasurementPreRegistration.hasSourceBackedWigertReference(
                trace, "Wigert's result is the relevant asymptotic comparator [HF123].")).isTrue();
        assertThat(MaxDivisorCountMeasurementPreRegistration.hasSourceBackedWigertReference(
                trace, "Wigert's result is relevant but uncited.")).isFalse();
        assertThat(MaxDivisorCountMeasurementPreRegistration.hasSourceBackedWigertReference(List.of(
                new ToolInvocation("search_literature", searchArgs("physical_mechanism", "wigert"),
                        "EVIDENCE [HF123] unrelated", 0), trace.get(1)), "[HF123]")).isFalse();
    }

    @Test
    void appliesOnlyToMaxDivisorCount() {
        assertThat(MaxDivisorCountMeasurementPreRegistration.from(
                new Conjecture("MATHEMATICS", "max_prime_gap", "wigert_bound", 1.0, 0.0))).isNull();
    }

    private static ToolInvocation held(long start, long size, int windows, double k, double c, double slack) {
        return new ToolInvocation("verify_conjecture", args(start, size, windows, k, c),
                "verify_conjecture: max_divisor_count <= +" + k + " * wigert_bound + +" + c
                        + " HELD across " + windows + " window(s) of size " + size + " starting at " + start
                        + ". worst margin: -0.1000 at window starting " + start + ". relative slack: " + slack, 0);
    }

    private static ToolInvocation growthLadder() {
        return new ToolInvocation("verify_growth",
                "{\"lhs\":\"max_divisor_count\",\"scale\":\"wigert\",\"target\":0.69315,"
                        + "\"window_start\":100000,\"window_size\":5000,\"num_windows\":20,"
                        + "\"scale_ladder\":true}",
                "verify_growth scale-ladder: max_divisor_count in wigert coords measured across 5 scales.", 0);
    }

    private static ToolInvocation refuted(long start, long size, int windows, double k, double c) {
        return new ToolInvocation("verify_conjecture", args(start, size, windows, k, c),
                "verify_conjecture: max_divisor_count <= +" + k + " * wigert_bound + " + c
                        + " REFUTED across " + windows + " window(s) of size " + size + " starting at " + start + ".", 0);
    }

    private static String args(long start, long size, int windows, double k, double c) {
        return "{\"lhs\":\"max_divisor_count\",\"rhs\":\"wigert_bound\",\"k\":" + k
                + ",\"c\":" + c + ",\"window_start\":" + start + ",\"window_size\":" + size
                + ",\"num_windows\":" + windows + "}";
    }

    private static String searchArgs(String first, String second) {
        return "{\"claim\":\"divisor Wigert relation\",\"subject_anchors\":[\"" + first
                + "\",\"" + second + "\"],\"claimed_relation\":\"finite envelope\","
                + "\"relevance_rationale\":\"Wigert reference\"}";
    }
}
