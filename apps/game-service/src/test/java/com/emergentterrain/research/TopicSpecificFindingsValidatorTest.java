package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 G6 — pins the topic-specific findings prose checks for
 * RLC R_c boilerplate, Thermal-Stress yield-strength mis-label,
 * and Arrhenius scientific-notation formatting.
 */
class TopicSpecificFindingsValidatorTest {

    @Test
    void rlcBoilerplateRcValueOf50Fires() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "The damping coefficient yields an R_c value of 50 across the swept range.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("R_c sentinel value");
    }

    @Test
    void rlcAllSixSentinelValuesFire() {
        for (int v : new int[]{10, 25, 50, 100, 200, 400}) {
            var reasons = TopicSpecificFindingsValidator.validate(
                    "PHYSICS", "RLC Resonance",
                    "Computed R_c value of " + v + ".");
            assertThat(reasons).as("R_c value of " + v).hasSize(1);
        }
    }

    @Test
    void rlcNonSentinelRcValuePasses() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "Computed R_c value of 73 across the swept range.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void rlcCheckSkippedForNonRlcTopic() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "Pendulum Period",
                "The configuration permits an R_c value of 50 in a separate context.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void thermalStressYieldStrengthFires() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "MATERIALS", "Thermal Stress",
                "The yield strength was observed to be 412 MPa at ΔT=200 K.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("yield strength");
        assertThat(reasons.get(0)).contains("thermal stress");
    }

    @Test
    void thermalStressCorrectLabelPasses() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "MATERIALS", "Thermal Stress",
                "The thermal stress σ_thermal was observed to be 412 MPa at ΔT=200 K.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void arrheniusScientificNotationFires() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "MATERIALS", "Arrhenius Reaction Rate",
                "The rate multiplier was 3.89×10^1 at 600 K.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("plain decimal");
    }

    @Test
    void arrheniusLowercaseXFires() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "MATERIALS", "Arrhenius",
                "Observed rate multiplier 1.20 x 10^2.");
        assertThat(reasons).hasSize(1);
    }

    @Test
    void arrheniusPlainDecimalPasses() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "MATERIALS", "Arrhenius Reaction Rate",
                "The rate multiplier was 38.9 at 600 K, 12.0 at 500 K, and 3.20 at 400 K.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void multipleSimultaneousMisusesAllFire() {
        // A pathological RLC paper that also slips in scientific notation.
        // RLC check fires; arrhenius check ignored (topic doesn't match).
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "R_c value of 100 yielding a multiplier of 1.5x10^2.");
        // Only RLC fires (arrhenius is topic-gated and topic is RLC, not Arrhenius).
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("R_c sentinel");
    }

    @Test
    void nullAndBlankFindingsReturnEmpty() {
        assertThat(TopicSpecificFindingsValidator.validate("PHYSICS", "RLC", (String) null)).isEmpty();
        assertThat(TopicSpecificFindingsValidator.validate("PHYSICS", "RLC", "")).isEmpty();
        assertThat(TopicSpecificFindingsValidator.validate("PHYSICS", "RLC", "   ")).isEmpty();
    }

    @Test
    void nullTopicReturnsEmpty() {
        assertThat(TopicSpecificFindingsValidator.validate("PHYSICS", null, "R_c value of 50"))
                .isEmpty();
    }

    @Test
    void topicMatchIsCaseInsensitive() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "physics", "rlc damping transition",
                "Computed R_c value of 25.");
        assertThat(reasons).hasSize(1);
    }

    // ---- Phase 13 E3: RLC Q-factor regime label mis-pairing ----

    @Test
    void e3_underdampedWithLowQFlagged() {
        // P-a328-458 signature: "Underdamped: Q=0.25" — Q=0.25 < 0.5 is
        // overdamped, not underdamped. Noun-vs-value mismatch.
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "The Q-factor sweep showed: Underdamped: Q=0.25, Critical: Q=0.50.");
        assertThat(reasons).anyMatch(r -> r.contains("regime mis-labelled")
                && r.contains("underdamped")
                && r.contains("0.25"));
    }

    @Test
    void e3_overdampedWithHighQFlagged() {
        // The mirror case — "Overdamped: Q=5.00" — Q=5.00 > 0.5 is underdamped.
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "Overdamped: Q=5.00 at the high-R end of the sweep.");
        assertThat(reasons).anyMatch(r -> r.contains("regime mis-labelled")
                && r.contains("overdamped")
                && r.contains("5.00"));
    }

    @Test
    void e3_correctLabelsNotFlagged() {
        // Correct usage: Underdamped: Q=5.00 (Q>0.5), Overdamped: Q=0.13 (Q<0.5).
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "Underdamped: Q=5.00 at R/R_c=0.2; Overdamped: Q=0.13 at R/R_c=4.");
        assertThat(reasons).noneMatch(r -> r.contains("regime mis-labelled"));
    }

    @Test
    void e3_pA328_458SignatureCatchesMismatchedRow() {
        // The literal P-a328-458 Computation 1 prose has three rows; only the
        // first ("Underdamped: Q=0.25") is mismatched. The third
        // ("Overdamped: Q=0.13") is correct (0.13 < 0.5).
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "RLC Damping Transition",
                "The Q-factor sweep reveals: "
                        + "Underdamped: Q=0.25; Critical: Q=0.50; Overdamped: Q=0.13.");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("0.25");
    }

    @Test
    void e3_nonRlcTopicNotFlagged() {
        var reasons = TopicSpecificFindingsValidator.validate(
                "PHYSICS", "Pendulum Breakdown Angle",
                "Underdamped: Q=0.25 (random text)");
        assertThat(reasons).isEmpty();
    }
}
