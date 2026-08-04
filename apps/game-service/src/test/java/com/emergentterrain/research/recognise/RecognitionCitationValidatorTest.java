package com.emergentterrain.research.recognise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 26 §26.4 — citation-precision validator tests. The
 * non-negotiable §26.6 acceptance bar is "zero hallucinated
 * cite-recognised:* handles across the first 30 papers"; this
 * class pins the validator's behavior so a regression there
 * lights up immediately.
 */
class RecognitionCitationValidatorTest {

    @Test
    void empty_inputs_validate() {
        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate("", List.of());
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.hallucinated()).isEmpty();
        assertThat(outcome.unused()).isEmpty();
    }

    @Test
    void null_inputs_validate_safely() {
        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(null, null);
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.hallucinated()).isEmpty();
        assertThat(outcome.unused()).isEmpty();
    }

    @Test
    void single_oeis_handle_matched_validates() {
        String prose = "The sequence 1, 1, 2, 3, 5, 8, 13 is recognised as " +
                "the Fibonacci numbers [cite-recognised:oeis:A000045].";
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("oeis", "Fibonacci numbers",
                        "cite-recognised:oeis:A000045", "A000045:identity", 50));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.hallucinated()).isEmpty();
        assertThat(outcome.unused()).isEmpty();
    }

    @Test
    void multiple_engines_all_matched_validates() {
        String prose = """
                Findings:
                The refined constant 1.20206... is ζ(3) [cite-recognised:pslq:zeta(3)].
                The integer sequence pattern matches A000040 [cite-recognised:oeis:A000040].
                The algebraic root √2+√3 satisfies x^4 − 10x^2 + 1
                [cite-recognised:lll:degree4:x^4_-_10*x^2_+_1].
                """;
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("pslq", "zeta(3)",
                        "cite-recognised:pslq:zeta(3)", "basis=BASIC", 0),
                new RecognitionEvidence("oeis", "primes",
                        "cite-recognised:oeis:A000040", "A000040:identity", 0),
                new RecognitionEvidence("lll", "x^4 - 10*x^2 + 1",
                        "cite-recognised:lll:degree4:x^4_-_10*x^2_+_1",
                        "degree=4", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.hallucinated()).isEmpty();
    }

    @Test
    void hallucinated_handle_detected() {
        // Paper claims a citation that never existed in the evidence chain.
        String prose = "The mysterious constant 0.789... is identified as " +
                "Champernowne's number [cite-recognised:pslq:champernowne].";
        // Evidence has a completely different citation — no champernowne row.
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("pslq", "pi",
                        "cite-recognised:pslq:pi", "basis=BASIC", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.hallucinated())
                .containsExactly("cite-recognised:pslq:champernowne");
        assertThat(outcome.unused())
                .containsExactly("cite-recognised:pslq:pi");
    }

    @Test
    void unused_evidence_reported_but_not_blocking() {
        // Engine produced a recognition but the narrator didn't cite it.
        // This is informational — not an ok=false condition.
        String prose = "Findings: The sum is bounded.";
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("oeis", "primes",
                        "cite-recognised:oeis:A000040", "A000040:identity", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();  // unused != failure
        assertThat(outcome.hallucinated()).isEmpty();
        assertThat(outcome.unused()).containsExactly("cite-recognised:oeis:A000040");
    }

    @Test
    void same_handle_cited_multiple_times_validates_once() {
        // Repeated citations of the same handle in prose should
        // collapse to one entry in the validator's view — multiple
        // hits of the same handle is fine as long as it resolves.
        String prose = """
                The constant ζ(3) [cite-recognised:pslq:zeta(3)] appears in
                Apéry's proof. We use ζ(3) [cite-recognised:pslq:zeta(3)]
                again in section 2.
                """;
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("pslq", "zeta(3)",
                        "cite-recognised:pslq:zeta(3)", "basis=BASIC", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();
    }

    @Test
    void malformed_handle_not_extracted() {
        // Strings that look like citations but don't match the strict
        // pattern shouldn't be flagged. The validator is regex-strict
        // on purpose to avoid false positives on prose like
        // "cite recognized as ζ(3)".
        String prose = "Reference: cite-recognized-as-zeta-3 (no colons).";
        List<RecognitionEvidence> evidence = List.of();

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.hallucinated()).isEmpty();
    }

    @Test
    void mixed_valid_and_hallucinated() {
        String prose = """
                Findings:
                The first hit recognises [cite-recognised:oeis:A000045] correctly,
                but the second [cite-recognised:pslq:fake_constant] is fabricated.
                """;
        List<RecognitionEvidence> evidence = List.of(
                new RecognitionEvidence("oeis", "Fibonacci",
                        "cite-recognised:oeis:A000045", "A000045:identity", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        // ANY hallucination flips ok to false.
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.hallucinated())
                .containsExactly("cite-recognised:pslq:fake_constant");
    }

    @Test
    void evidence_with_null_handle_skipped_gracefully() {
        String prose = "[cite-recognised:oeis:A000045]";
        List<RecognitionEvidence> evidence = new java.util.ArrayList<>();
        // null entries shouldn't crash the validator
        evidence.add(null);
        evidence.add(new RecognitionEvidence("oeis", "Fibonacci",
                "cite-recognised:oeis:A000045", "A000045:identity", 0));

        RecognitionCitationValidator.ValidationOutcome outcome =
                RecognitionCitationValidator.validate(prose, evidence);

        assertThat(outcome.ok()).isTrue();
    }
}
