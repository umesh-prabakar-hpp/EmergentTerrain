package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 E6 — each test is anchored to one of the four acceptance
 * cases in the phase doc, plus negative cases (no unit-anchored numeric,
 * blank input) and the structured-findings overload.
 */
class UncertaintyDisclosureValidatorTest {

    // ── Acceptance: phase doc cases ────────────────────────────────────

    @Test
    void unitAnchoredNumericWithoutUncertaintyFlagged() {
        // Phase doc acceptance #1: "max σ_y ≈ 2377 MPa" (no uncertainty) → flag.
        // The ≈ is a hedge, not a quantitative bound.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "max σ_y ≈ 2377 MPa across the grain-size sweep.");
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("no uncertainty disclosure");
    }

    @Test
    void plusMinusNumericPasses() {
        // Phase doc acceptance #2: "max σ_y ≈ 2377 MPa ± 12 MPa" → pass.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "max σ_y ≈ 2377 MPa ± 12 MPa across the grain-size sweep.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void exactClosedFormMarkerPasses() {
        // Phase doc acceptance #3: "E₁/kT = 0.118 [exact closed form]" → pass.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Ground-state ratio E₁/kT = 0.118 J [exact closed form].");
        assertThat(reasons).isEmpty();
    }

    @Test
    void trailingExactQualifierPasses() {
        // Phase doc acceptance #4: "η_Carnot = 1 − T_c/T_h, exact" → pass.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Efficiency η = 0.50 at T_h = 720 K, exact reversible bound.");
        assertThat(reasons).isEmpty();
    }

    // ── Additional accepted forms ─────────────────────────────────────

    @Test
    void exactlyKeywordPasses() {
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "The recovery σ = 5.670e-8 J K⁻⁴ matches Stefan-Boltzmann exactly.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void closedFormHyphenatedPasses() {
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Period T = 2.375 s, closed-form prediction of the small-angle limit.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void toWithinPercentPasses() {
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "ε recovered as 0.111 J to within 5% of the literature value.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void plusMinusWithScientificNotationPasses() {
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "E₁ = 7.80e-22 J ± 0.02e-22 across the L sweep.");
        assertThat(reasons).isEmpty();
    }

    // ── Negative: no unit-anchored numerics, nothing to check ─────────

    @Test
    void noUnitAnchoredNumericReturnsEmpty() {
        // Plain prose with no values to discipline. Pass.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "The pendulum period rose smoothly across the sweep, consistent with "
                        + "the theoretical prediction.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void plainIntegersWithoutUnitsReturnEmpty() {
        // Counts / indices without units are not "salient" — no discipline triggered.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Across 5 trials and 10 iterations the result held.");
        assertThat(reasons).isEmpty();
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    void nullInputReturnsEmpty() {
        assertThat(UncertaintyDisclosureValidator.validate((String) null)).isEmpty();
    }

    @Test
    void blankInputReturnsEmpty() {
        assertThat(UncertaintyDisclosureValidator.validate("")).isEmpty();
        assertThat(UncertaintyDisclosureValidator.validate("   ")).isEmpty();
    }

    @Test
    void approximatelySymbolAloneStillFlags() {
        // Per phase doc: "≈" alone is a hedge, not a bound. Even with the
        // hedge present, missing ± / exact / to within still flags.
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Peak observed at λ ≈ 950 nm.");
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void approximatelyWordAloneStillFlags() {
        List<String> reasons = UncertaintyDisclosureValidator.validate(
                "Recovered the value approximately 100 MPa from the sweep.");
        assertThat(reasons).isNotEmpty();
    }

    // ── Structured-findings overload ──────────────────────────────────

    @Test
    void structuredFindingsMissingInAnyBlockFlags() {
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Recovered σ within 1.92% of the reference value.");
        blocks.put(2, "Peak yield stress 149 MPa at d=20.75 µm.");
        // Block 2 has unit-anchored numerics; block 1 has no ± / exact /
        // to within (the "%" is unit-anchored numeric too without ±).
        // Combined prose has no uncertainty indicator → flag.
        List<String> reasons = UncertaintyDisclosureValidator.validate(blocks);
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void structuredFindingsWithUncertaintyInOneBlockPasses() {
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Recovered σ to within 5% of the reference value.");
        blocks.put(2, "Peak yield stress 149 MPa at d=20.75 µm.");
        // The "to within 5%" in block 1 satisfies the paper-level discipline.
        List<String> reasons = UncertaintyDisclosureValidator.validate(blocks);
        assertThat(reasons).isEmpty();
    }

    @Test
    void structuredFindingsNullMapReturnsEmpty() {
        assertThat(UncertaintyDisclosureValidator.validate(
                (java.util.Map<Integer, String>) null)).isEmpty();
    }
}
