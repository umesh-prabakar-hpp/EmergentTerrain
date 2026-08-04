package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 D6 — each test is anchored to a specific paper ID in
 * REVIEW_REPORT refresh #3 (research/a3289005, 2026-05-15 12:35) so the
 * corpus failure mode the validator targets stays legible.
 */
class ClosingProseValidatorTest {

    // ── Validator-level checks ─────────────────────────────────────────────

    @Test
    void pA328_416_mertensBoilerplateFlagged() {
        // Mertens Partial Sums has a known bound (Mertens conjecture / O(x^(1/2+ε))).
        List<String> reasons = ClosingProseValidator.validate(
                "MATHEMATICS", "Mertens Partial Sums",
                "Results consistent with the literature. Note that no prior theoretical "
                        + "bound constrains the expected value in this region.");
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0))
                .contains("Mertens Partial Sums")
                .contains("known textbook bound");
    }

    @Test
    void pA328_422_beerLambertBoilerplateFlagged() {
        List<String> reasons = ClosingProseValidator.validate(
                "CHEMISTRY", "Beer-Lambert Absorbance",
                "ε recovery proceeds without surprise; no prior theoretical bound "
                        + "constrains the expected value in this region.");
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void pA328_423_stefanBoltzmannBoilerplateFlagged() {
        List<String> reasons = ClosingProseValidator.validate(
                "PHYSICS", "Stefan-Boltzmann Total Flux",
                "Observed temperatures span the sweep. No prior theoretical bound "
                        + "constrains the expected value.");
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void pA328_427_dopplerBoilerplateFlagged() {
        List<String> reasons = ClosingProseValidator.validate(
                "INTERDISCIPLINARY", "Acoustic Doppler Frequency Shift",
                "Frequency shift observed across the sample. No prior theoretical bound "
                        + "constrains the expected value in this region.");
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void pA328_432_hallEffectBoilerplateFlagged() {
        List<String> reasons = ClosingProseValidator.validate(
                "INTERDISCIPLINARY", "Hall-Effect Carrier Sign",
                "Carrier sign behaviour confirmed. No prior theoretical bound "
                        + "constrains the expected value in this region.");
        assertThat(reasons).isNotEmpty();
    }

    // ── No false positives on genuinely-open conjectures ──────────────────

    @Test
    void twinPrimeBoilerplateNotFlagged() {
        // Twin Prime Clustering is an active conjecture — the boilerplate is correct prose.
        List<String> reasons = ClosingProseValidator.validate(
                "MATHEMATICS", "Twin Prime Clustering",
                "The pattern beyond 10^5 remains open; no prior theoretical bound "
                        + "constrains the expected value in this region.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void collatzBoilerplateNotFlagged() {
        // Collatz is famously open. Boilerplate is fine.
        List<String> reasons = ClosingProseValidator.validate(
                "MATHEMATICS", "Collatz Stopping Time",
                "Stopping-time distribution beyond surveyed range remains an "
                        + "open question; no prior theoretical bound constrains the "
                        + "expected value in this region.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void goldbachBoilerplateNotFlagged() {
        // Goldbach conjecture — open.
        List<String> reasons = ClosingProseValidator.validate(
                "MATHEMATICS", "Goldbach Partition Survey",
                "Partition counts continue without anomaly; no prior theoretical bound "
                        + "constrains the expected value.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void engineConstructedBridgeNotFlagged() {
        // INTERDISCIPLINARY engine-constructed bridges have no real-world textbook
        // bound — their coupling equations are parameterised inventions. Boilerplate
        // is correct prose on those topics.
        List<String> reasons = ClosingProseValidator.validate(
                "INTERDISCIPLINARY", "Collatz-RLC Depth Resonance",
                "Coupling depth ratios diverge as expected; no prior theoretical bound "
                        + "constrains the expected value in this region.");
        assertThat(reasons).isEmpty();
    }

    // ── Boilerplate absent → no false positives ───────────────────────────

    @Test
    void conclusionWithoutBoilerplateNotFlagged() {
        // Bounded topic but no boilerplate sentence — pass.
        List<String> reasons = ClosingProseValidator.validate(
                "PHYSICS", "Stefan-Boltzmann Total Flux",
                "Recovered σ within 5% of the reference value. Conclusion confirmed.");
        assertThat(reasons).isEmpty();
    }

    @Test
    void shortVariantWithoutTrailingClauseStillFlagged() {
        // P-a328-427 truncated mid-sentence after "expected value" — match that form too.
        List<String> reasons = ClosingProseValidator.validate(
                "PHYSICS", "Stefan-Boltzmann Total Flux",
                "Σ-recovery proceeded. No prior theoretical bound constrains the expected value.");
        assertThat(reasons).isNotEmpty();
    }

    // ── Null / blank safety ────────────────────────────────────────────────

    @Test
    void nullInputsReturnEmptyReasonList() {
        assertThat(ClosingProseValidator.validate(null, "t", "c")).isEmpty();
        assertThat(ClosingProseValidator.validate("d", null, "c")).isEmpty();
        assertThat(ClosingProseValidator.validate("d", "t", null)).isEmpty();
    }

    @Test
    void blankConclusionReturnsEmptyReasonList() {
        assertThat(ClosingProseValidator.validate("PHYSICS", "Stefan-Boltzmann Total Flux", ""))
                .isEmpty();
        assertThat(ClosingProseValidator.validate("PHYSICS", "Stefan-Boltzmann Total Flux", "   "))
                .isEmpty();
    }

    // ── TextbookBoundRegistry direct checks ───────────────────────────────

    @Test
    void registryRecognisesAllFiveNamedReportPapers() {
        // The five textbook-bound topics from REVIEW_REPORT refresh #3 §1 rec #5.
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS",       "Mertens Partial Sums")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("CHEMISTRY",         "Beer-Lambert Absorbance")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("PHYSICS",           "Stefan-Boltzmann Total Flux")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("INTERDISCIPLINARY", "Acoustic Doppler Frequency Shift")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("INTERDISCIPLINARY", "Hall-Effect Carrier Sign")).isTrue();
    }

    @Test
    void registryExcludesActiveConjectures() {
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS", "Twin Prime Clustering")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS", "Collatz Stopping Time")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS", "Goldbach Partition Survey")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS", "Aliquot Sequence Behaviour")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound("MATHEMATICS", "Happy Number Distribution")).isFalse();
    }

    @Test
    void registryExcludesEngineConstructedBridges() {
        // INTERDISCIPLINARY engine-constructed bridges deliberately stay out of the
        // registry — their coupling equations are parameterised inventions.
        assertThat(TextbookBoundRegistry.hasKnownBound(
                "INTERDISCIPLINARY", "Collatz-RLC Depth Resonance")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound(
                "INTERDISCIPLINARY", "Prime-Gap Defect Thermal Loading")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound(
                "INTERDISCIPLINARY", "Happy-Number Packing Anomaly")).isFalse();
    }

    @Test
    void registryLookupIsCaseInsensitiveOnBothFields() {
        assertThat(TextbookBoundRegistry.hasKnownBound("physics", "stefan-boltzmann total flux")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("PHYSICS", "STEFAN-BOLTZMANN TOTAL FLUX")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound("Physics", "Stefan-Boltzmann Total Flux")).isTrue();
    }

    @Test
    void registryNormalisesSchrodingerSpellings() {
        // Engine canonical key is "Schrodinger Infinite Well Energy"; reviewers
        // sometimes spell it "Schrödinger Infinite Well". Both should resolve.
        assertThat(TextbookBoundRegistry.hasKnownBound(
                "PHYSICS", "Schrödinger Infinite Well")).isTrue();
        assertThat(TextbookBoundRegistry.hasKnownBound(
                "PHYSICS", "Schrodinger Infinite Well Energy")).isTrue();
    }

    @Test
    void registryNullInputsReturnFalse() {
        assertThat(TextbookBoundRegistry.hasKnownBound(null, "Stefan-Boltzmann Total Flux")).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound("PHYSICS", null)).isFalse();
        assertThat(TextbookBoundRegistry.hasKnownBound(null, null)).isFalse();
    }
}
