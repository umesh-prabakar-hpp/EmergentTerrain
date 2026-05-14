package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 B2 — corpus-anchored magnitude-bound checks. */
class MagnitudeRangeValidatorTest {

    // -------- Composite failure: > 3 GPa is unphysical --------

    @Test
    void pA328_2_compositeStressTwelveGPaFlagged() {
        // P-a328-2/8/12/42/63/84/86: max stress ≈ 12 020 MPa from the linear σ = Vf·Ef·ε_y model.
        // The validator stops on the first violation, so the reported value is the
        // earliest above-bound entry in the series (Vf=80%→9620MPa here).
        String findings = "Composite failure (...): Vf=80%→9620MPa Vf=90%→12020MPa. Stress rises linearly.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0))
                .contains("Composite Failure Mode")
                .contains("9620")
                .contains("3000");
    }

    @Test
    void compositeStressUnderThreeGPaPassesUnflagged() {
        String findings = "Composite failure (...): Vf=20%→820MPa Vf=40%→1620MPa Vf=60%→2420MPa.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- VdW composite crossover: > 1.5 GPa is unphysical --------

    @Test
    void pA328_7_vdwSevenPointEightGPaFlagged() {
        // P-a328-7: 7.8 GPa stress.
        String findings = "VdW-induced composite stress reached 7800 MPa at P=80 atm.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "INTERDISCIPLINARY", "Van-der-Waals Composite Crossover", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("7800");
    }

    @Test
    void vdwUnderOnePointFiveGPaPassesUnflagged() {
        String findings = "VdW-induced composite stress reached 920 MPa at P=80 atm.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "INTERDISCIPLINARY", "Van-der-Waals Composite Crossover", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- Arrhenius ratio > 12× is unphysical for the +10K rule --------

    @Test
    void arrheniusEighteenTimesFlagged() {
        // Synthetic — far above any realistic +10K ratio; would indicate a simulator bug.
        String findings = "Arrhenius (T=200 K): Ea=10kJ→1.04× Ea=50kJ→2.13× Ea=130kJ→18.5×.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Arrhenius Rule Validity", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("Arrhenius Rule Validity");
    }

    @Test
    void arrheniusUnderTwelveTimesPassesUnflagged() {
        // P-a328-31's correct 6.7× at T=342K, Ea=189kJ — well under the bound.
        String findings = "Arrhenius (T=342 K): Ea=10kJ→1.04× Ea=50kJ→1.65× Ea=189kJ→6.7×.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Arrhenius Rule Validity", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- Tolerance / null safety / domain isolation --------

    @Test
    void nullArgumentsReturnEmptyReasonList() {
        assertThat(MagnitudeRangeValidator.validate(null, "T", "f")).isEmpty();
        assertThat(MagnitudeRangeValidator.validate("D", null, "f")).isEmpty();
        assertThat(MagnitudeRangeValidator.validate("D", "T", (String) null)).isEmpty();
    }

    @Test
    void unknownDomainTopicReturnsEmpty() {
        // No bound registered for this pair — pass through with no violation.
        String findings = "Some findings with 99999 MPa value that would otherwise trip a bound.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATHEMATICS", "Prime Constellation Search", findings);
        assertThat(reasons).isEmpty();
    }

    @Test
    void domainAndTopicMustBothMatch() {
        // Composite-failure bound is on MATERIALS:Composite Failure Mode. Other
        // topic, even with matching MPa stresses, must not trigger that bound.
        String findings = "Some materials topic emitting 12020 MPa.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Thermal Stress Safety Window", findings);
        assertThat(reasons).isEmpty();
    }

    // ---- Phase 7 F2: Map-shaped overload concatenates blocks ----

    @Test
    void mapOverloadConcatenatesBlocksBeforeChecking() {
        // The offending magnitude appears in computation 2; the Map overload
        // must concatenate before applying the regex so the bound fires.
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "First block — modest 800 MPa stress, all fine.");
        blocks.put(2, "Second block: Vf=90%→12020MPa, an unphysical value.");
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", blocks);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("12020");
    }

    @Test
    void mapOverloadOnSingleEntryMapBehavesIdenticallyToStringPath() {
        // Flag-off bit-identical: a single-entry map carrying the legacy prose
        // produces the same reason list as the legacy String overload.
        String prose = "Composite failure (...): Vf=80%→9620MPa Vf=90%→12020MPa.";
        java.util.Map<Integer, String> singleton = java.util.Map.of(1, prose);
        List<String> stringReasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", prose);
        List<String> mapReasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", singleton);
        assertThat(mapReasons).isEqualTo(stringReasons);
    }
}
