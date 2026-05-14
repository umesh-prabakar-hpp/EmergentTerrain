package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 B4 — corpus-anchored cross-domain non-sequitur checks. */
class CrossDomainHypothesisGateTest {

    // -------- MATHEMATICS declared, materials/physics drift --------

    @Test
    void pA328_18_mathDeclaredButHypothesisIsThermalFlagged() {
        // P-a328-18: title is math (divisor sums); hypothesis is materials (thermal stress).
        String hypothesis = "Aliquot divisor sums in the range will modulate thermal stress in composite layers.";
        Optional<String> result = CrossDomainHypothesisGate.validate("MATHEMATICS", hypothesis);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("thermal stress");
    }

    @Test
    void mathDomainCompositeMarkerFlagged() {
        // Markers are matched in declaration order; the first hit ('fibre' here) is reported.
        String hypothesis = "Goldbach partitions will correlate with composite fibre yield strength.";
        Optional<String> result = CrossDomainHypothesisGate.validate("MATHEMATICS", hypothesis);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("fibre");
    }

    @Test
    void mathDomainPureMathHypothesisPasses() {
        String hypothesis = "Twin prime density in [N, 2N) will converge to the Hardy-Littlewood asymptotic.";
        Optional<String> result = CrossDomainHypothesisGate.validate("MATHEMATICS", hypothesis);
        assertThat(result).isEmpty();
    }

    // -------- MATERIALS / PHYSICS declared, math drift --------

    @Test
    void pA328_1_materialsHypothesisWithPrimeGapMarkerFlagged() {
        // P-a328-1: prime gaps → CTE, MATERIALS topic. Hypothesis mentions prime gaps.
        String hypothesis = "Crystal defect periodicity derived from prime gap spacings produces measurable CTE shifts.";
        Optional<String> result = CrossDomainHypothesisGate.validate("MATERIALS", hypothesis);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("prime gap");
    }

    @Test
    void pA328_16_physicsHypothesisWithCollatzMarkerFlagged() {
        // P-a328-16: pendulum-breakdown angle → Collatz depth (physics topic mentions math).
        String hypothesis = "Pendulum breakdown angle correlates with Collatz trajectory depth.";
        Optional<String> result = CrossDomainHypothesisGate.validate("PHYSICS", hypothesis);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("Collatz");
    }

    @Test
    void materialsHypothesisPureMaterialsPasses() {
        String hypothesis = "For Ef/Em=10, Reuss bound transverse stiffness doubles at Vf well above 0.5.";
        Optional<String> result = CrossDomainHypothesisGate.validate("MATERIALS", hypothesis);
        assertThat(result).isEmpty();
    }

    // -------- INTERDISCIPLINARY is intentionally cross-domain --------

    @Test
    void interdisciplinaryDomainSkipsTheGate() {
        // The engine generates interdisciplinary topics (Prime-Gap Defect Thermal
        // Loading etc.) on purpose. The gate must not block them.
        String hypothesis = "Prime gap defect spacing maps to thermal stress yield in steel.";
        Optional<String> result = CrossDomainHypothesisGate.validate("INTERDISCIPLINARY", hypothesis);
        assertThat(result).isEmpty();
    }

    // -------- Null safety --------

    @Test
    void nullArgumentsReturnEmpty() {
        assertThat(CrossDomainHypothesisGate.validate(null, "h")).isEmpty();
        assertThat(CrossDomainHypothesisGate.validate("MATHEMATICS", null)).isEmpty();
    }

    // -------- Phase 7 FX.6: new domains are skipped (orthogonal to math/phys/mat markers) --------

    @Test
    void biologyDomainSkipsGate() {
        // BIOLOGY's natural vocabulary (Verhulst, Lotka-Volterra, Michaelis-Menten,
        // Kleiber, Hardy-Weinberg) doesn't intersect the gate's math/physics/materials
        // markers, but a phrase like "thermal" in a Kleiber metabolism paper would
        // false-positive without an exception.
        String hypothesis = "Kleiber's law predicts metabolic rate from body mass via "
                + "thermal regulation across mammalian thermal-mass envelopes.";
        assertThat(CrossDomainHypothesisGate.validate("BIOLOGY", hypothesis)).isEmpty();
    }

    @Test
    void chemistryDomainSkipsGate() {
        // CHEMISTRY uses thermal-equilibrium language freely.
        String hypothesis = "Buffer pH stays constant as concentration ratios shift; thermal drift "
                + "is negligible compared to the Henderson-Hasselbalch term.";
        assertThat(CrossDomainHypothesisGate.validate("CHEMISTRY", hypothesis)).isEmpty();
    }

    @Test
    void architectureTerrestrialSkipsGate() {
        // Architecture terrestrial naturally talks about thermal mass, fibre composites, etc.
        String hypothesis = "Concrete fibre-reinforced walls regulate thermal stress under "
                + "extreme atmospheric loads on terrestrial surfaces.";
        assertThat(CrossDomainHypothesisGate.validate("ARCHITECTURE_TERRESTRIAL", hypothesis)).isEmpty();
    }

    @Test
    void architectureLiquidSkipsGate() {
        String hypothesis = "Composite hulls experience thermal stress from temperature gradients "
                + "across liquid layers of varying density.";
        assertThat(CrossDomainHypothesisGate.validate("ARCHITECTURE_LIQUID", hypothesis)).isEmpty();
    }

    @Test
    void architectureSpaceSkipsGate() {
        String hypothesis = "Vacuum thermal radiative balance for habitat modules with composite "
                + "fibre-shielded walls under solar particle flux.";
        assertThat(CrossDomainHypothesisGate.validate("ARCHITECTURE_SPACE", hypothesis)).isEmpty();
    }
}
