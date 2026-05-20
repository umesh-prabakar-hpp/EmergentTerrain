package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21 §21.4 — pins the {@link PremiseValidator} rules.
 *
 * <p>Acceptance per the phase doc: P-a328-693's hypothesis would never
 * have reached a tool call. The {@link #tirIndicesInverted_P693_rejected}
 * test feeds that paper's exact hypothesis text and asserts a REJECT.
 */
class PremiseValidatorTest {

    // ────────────── Snell / TIR ──────────────

    @Test
    void tirIndicesInverted_P693_rejected() {
        // Exact prose from research/a3289005/2026-05-19_07-26-47_…_P-a328-693.md
        String hypothesis = "At a glass–air interface with n₁=1.50 and n₂≈1.577, "
                + "the critical angle θ_c=arcsin(n₂/n₁) will align with observed "
                + "total internal reflection within 2°.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Snell Critical Angle Dispersion", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("tir_indices_inverted");
        assertThat(reject.explanation()).contains("n₁ > n₂");
        assertThat(reject.explanation()).contains("1.5").contains("1.577");
    }

    @Test
    void tirIndicesCorrect_glassToAir_accepted() {
        // Light from glass into air — TIR is possible.
        String hypothesis = "At a glass–air interface with n₁=1.50 and n₂=1.00, "
                + "the critical angle θ_c=arcsin(n₂/n₁)≈41.8°.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void tirRule_handlesAsciiSubscripts() {
        // Same content, but the LLM wrote "n1" / "n2" instead of "n₁" / "n₂".
        String hypothesis = "Given n1=1.5 and n2=1.6 across the interface, the "
                + "critical angle is well-defined.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
    }

    @Test
    void tirRule_skipsWhenIndicesNotMentioned() {
        // Topic matches but the hypothesis doesn't name n1/n2 — nothing to check.
        String hypothesis = "We measure the critical angle as a function of wavelength.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void nonSnellTopic_skipsTirRule() {
        // Even if n1>n2 appears in unrelated prose, only snell/TIR topics trigger.
        String hypothesis = "n₁=1.7 and n₂=1.9 are coupling constants.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Pendulum Breakdown Angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void tirRule_handlesEqualIndices() {
        // n1==n2 is also invalid (no refraction at all → no critical angle).
        String hypothesis = "At n₁=1.5 and n₂=1.5 the interface produces no refraction.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
    }

    // ────────────── Twin-prime / Hardy-Littlewood ──────────────

    @Test
    void twinPrimeWithBothDensitiesNamed_accepted() {
        String hypothesis = "The twin-prime density in [N, N+W) approaches the "
                + "Hardy-Littlewood constant; the overall prime density follows 1/log N.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "twin-prime-density", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void twinPrimeWithOnlyTwinDensity_rejected() {
        // P-a328-685 signature: mentions twin density without disambiguating
        // from overall density, then conflates the two in findings.
        String hypothesis = "We expect the twin-prime density in [6644, 7004) to "
                + "reach 0.13, matching prior observations.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Prime Constellation Density", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("twin_density_unqualified");
    }

    @Test
    void twinPrimeRule_skipsWhenDensityNotMentioned() {
        String hypothesis = "Count twin-prime constellations in this range.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "twin-prime-density", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    // ────────────── topic normalisation / pass-through ──────────────

    @Test
    void normaliseTopic_lowercasesAndHyphenates() {
        assertThat(PremiseValidator.normaliseTopic("Snell Critical Angle"))
                .isEqualTo("snell-critical-angle");
        assertThat(PremiseValidator.normaliseTopic("snell_critical_angle"))
                .isEqualTo("snell-critical-angle");
        assertThat(PremiseValidator.normaliseTopic(null)).isEmpty();
    }

    @Test
    void unknownTopic_alwaysAccepted() {
        PremiseValidator.Result r = PremiseValidator.validate(
                "Some Brand New Topic", "n₁=2.0 and n₂=3.0 are arbitrary indices.");
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void nullOrBlankHypothesis_alwaysAccepted() {
        assertThat(PremiseValidator.validate("snell-critical-angle", null))
                .isInstanceOf(PremiseValidator.Result.Ok.class);
        assertThat(PremiseValidator.validate("snell-critical-angle", ""))
                .isInstanceOf(PremiseValidator.Result.Ok.class);
        assertThat(PremiseValidator.validate("snell-critical-angle", "   "))
                .isInstanceOf(PremiseValidator.Result.Ok.class);
    }
}
