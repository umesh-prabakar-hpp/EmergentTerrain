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

    @Test
    void tirRule_proseForm_P736_rejected() {
        // Exact prose from research/a3289005/2026-05-20_…_P-a328-736.md.
        // The original subscript-only regexes missed this case because the
        // LLM described the indices in natural language. The prose-form
        // fallback added 2026-05-20 catches it.
        String hypothesis = "For an interface where the incident medium has refractive "
                + "index 1.50 and the adjoining medium has a refractive index near 1.565, "
                + "the critical angle obtained from Snell's law should lie within two "
                + "degrees of the experimentally observed onset of total internal reflection.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Snell Critical Angle Dispersion", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("tir_indices_inverted");
        assertThat(reject.explanation()).contains("1.5").contains("1.565");
    }

    @Test
    void tirRule_proseForm_correctDirection_accepted() {
        // Light from a denser to a rarer medium — TIR is possible.
        String hypothesis = "The incident medium has refractive index 1.50, and the "
                + "adjoining medium has a refractive index of 1.00 (vacuum).";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void tirRule_proseForm_alternateVocab_rejected() {
        // "first / second" pair instead of "incident / adjoining".
        String hypothesis = "The first medium has refractive index 1.33 and the second "
                + "medium has refractive index 1.50.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "snell-critical-angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
    }

    // ────────────── hypothesis completeness (2026-05-20) ──────────────

    @Test
    void completeness_P755_givenSuffix_rejected() {
        // P-a328-755 exact tail: "...the conjectured asymptotic 14.3% [call_1] given"
        String hypothesis = "The happy number density in the range [4884, 5034) will "
                + "remain close to the conjectured asymptotic 14.3% [call_1] given";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Happy Number Distribution", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("hypothesis_truncated");
        assertThat(reject.explanation()).contains("given");
    }

    @Test
    void completeness_P727_basedOnSuffix_rejected() {
        // P-a328-727 exact tail: "...is expected to be less than 20°, based on"
        String hypothesis = "For a pendulum of length L=1.56 m, the small-angle period "
                + "will diverge above a critical angle θ_c, which is expected to be less "
                + "than 20°, based on";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Pendulum Breakdown Angle", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Reject.class);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("hypothesis_truncated");
        assertThat(reject.explanation()).contains("based on");
    }

    @Test
    void completeness_completedSentence_accepted() {
        // A standard well-formed hypothesis ending with a period — must not
        // false-positive even though "based on" appears in the middle.
        String hypothesis = "For an RLC circuit with L=1.8 mH and C=1.46 µF, the damping "
                + "transition occurs at R_c=70 Ω based on the series-circuit closed form.";
        PremiseValidator.Result r = PremiseValidator.validate(
                "RLC Damping Transition", hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void completeness_missingPeriodButCompleteClause_accepted() {
        // Stylistic — author forgot the final period but the clause is
        // self-contained. The rule must not police missing periods alone,
        // only ends-with-subordinator. Last word "samples" is a noun, not
        // a continuation marker.
        String hypothesis = "We expect twin-prime density 0.143 across n=40 samples";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Twin Prime Density", hypothesis);
        // Twin-prime rule will reject this for unqualified density, but the
        // completeness check must NOT fire — the reason code disambiguates.
        if (r instanceof PremiseValidator.Result.Reject reject) {
            assertThat(reject.reasonCode()).isNotEqualTo("hypothesis_truncated");
        }
    }

    @Test
    void completeness_endsWithCallMarker_accepted() {
        // Common shape: numeric value immediately followed by [call_N] and
        // nothing else. Not a truncation.
        String hypothesis = "The density in this interval will match the prior result of "
                + "0.143 [call_1]";
        // Twin-prime topic will catch unqualified "density" — but completeness
        // must pass through.
        PremiseValidator.Result r = PremiseValidator.validateCompleteness(hypothesis);
        assertThat(r).isInstanceOf(PremiseValidator.Result.Ok.class);
    }

    @Test
    void completeness_runsBeforeTopicRules() {
        // A truncated TIR hypothesis must reject on completeness, NOT TIR —
        // we want the most upstream reason code so retries fix the right
        // problem first.
        String hypothesis = "At n₁=1.50 and n₂=1.577, the critical angle is computed based on";
        PremiseValidator.Result r = PremiseValidator.validate(
                "Snell Critical Angle Dispersion", hypothesis);
        var reject = (PremiseValidator.Result.Reject) r;
        assertThat(reject.reasonCode()).isEqualTo("hypothesis_truncated");
    }

    @Test
    void completeness_acceptsCommonClauseEnders() {
        // Sanity: hypotheses with common technical phrasings that COULD
        // look like truncations to a naive heuristic still pass.
        String[] complete = {
            "Computed values lie within the swept range.",
            "The result is bounded by the theoretical asymptote.",
            "Sample size n=200 across the interval [50, 250).",
            "We measure θ_c < 30°.",
            "Range optimum near 25° with quadratic drag.",
        };
        for (String h : complete) {
            PremiseValidator.Result r = PremiseValidator.validateCompleteness(h);
            assertThat(r)
                    .as("'%s' should be Ok", h)
                    .isInstanceOf(PremiseValidator.Result.Ok.class);
        }
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
