package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 18 §18.2 (P8d) — "math right, prose fabricated" detector.
 * Test corpus is the refresh-#2 evidence named in the phase doc.
 */
class UnsourcedClaimValidatorTest {

    // ── True positives: claims that should flag ──────────────────────────────

    @Test
    void p603_snellDerivativeWithoutCallRef_flagged() {
        // P-a328-603 Snell: call_3 returned arcsin(1.49/1.50) = 83.3804°
        // and prose then invented "331° per unit n₂ derivative" with no
        // supporting [call_N] in the same sentence.
        String findings = "Call 3 yielded the exact critical angle [call_3]. "
                + "The derivative of the angle with respect to n₂ is 331° per unit "
                + "across the small-perturbation regime.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:derivative"));
    }

    @Test
    void p603_snellErrorClaimWithoutCallRef_flagged() {
        // The "1.5° maximum observed error" sentence from P-a328-603.
        String findings = "The exact critical angle is 83.3804° [call_3]. "
                + "The maximum observed error across the test corpus is 1.5°.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:error"));
    }

    @Test
    void p313_phantomIndependentVerificationWithoutCallRef_flagged() {
        // P-b72c-313: prose invented "the professor's independent verification
        // confirmed the same count of 11 happy primes" — no such tool call
        // exists in the trace. The "[600, 1000)" interval is NOT a [call_N] ref,
        // and the digit 11 means it isn't honest absence-hedging.
        String findings = "The scan returned 0.028 [call_5]. "
                + "The professor's independent verification confirmed the same "
                + "count of 11 happy primes in [600, 1000).";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void actorAttributedVerification_flagged() {
        // Actor-attributed fabrication beyond "professor": an oracle/referee
        // claimed to have confirmed a result with no [call_N].
        String findings = "The bound holds on the slice. "
                + "The oracle separately confirmed the count of 11 in a held-out check.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void standaloneIndependentVerificationNoun_flagged() {
        // The NOUN "independent verification" (not the verify_conjecture tool)
        // asserted with no [call_N].
        String findings = "An independent verification established the same "
                + "count of 11 happy primes.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void legitIndependentVerifyConjectureSweeps_doesNotFlag() {
        // 2026-06-08 live FP (P-b72c-317): an accurate description of the
        // author's OWN real tool calls — the verify_conjecture tool name and the
        // adjective "verified" are NOT a claimed external verification.
        String f1 = "Three independent verify_conjecture sweeps refuted the "
                + "conjecture on all 30 windows tested.";
        String f2 = "The bound is violated across all 30 independently verified "
                + "windows spanning x in [200000, 1950000).";
        assertThat(UnsourcedClaimValidator.validate(f1, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
        assertThat(UnsourcedClaimValidator.validate(f2, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void realToolVerificationWithCallRef_passes() {
        // A genuine verify_conjecture call carries its [call_N] in-sentence.
        String findings = "An independent verification via verify_conjecture "
                + "[call_2] confirmed the bound held on the held-out slice.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void independentVariableProse_doesNotFlagPhantomVerification() {
        // "independent" not adjacent to a verify-verb must NOT fire — guards
        // against FPs on "independent variable" / "separate analysis".
        String findings = "The independent variable was the window start, "
                + "and a separate analysis of 100 windows showed no trend.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void absenceOfIndependentVerification_exempt() {
        // Honest hedging: negation cue + no digit → absence-meta exemption.
        String findings = "No independent verification of this count was "
                + "performed, so the value is reported as provisional.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:phantom_verification"));
    }

    @Test
    void uncertaintyWithoutCallRef_flagged() {
        String findings = "Call 1 returned the canonical value [call_1]. "
                + "Quoted uncertainty for the derived quantity is ±0.02 across the regime.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:uncertainty"));
    }

    @Test
    void barePlusMinusWithoutCallRef_flagged() {
        String findings = "Result: 1.4321 measured via Newton-Raphson. "
                + "Variation across the regime: ±0.05 at the crossover boundary.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:plus_minus"));
    }

    @Test
    void claimInConclusionAlso_flagged() {
        // Conclusion text is concatenated after Findings, so a claim there
        // (with no [call_N]) must also flag.
        String findings = "Calls 1 and 2 ran cleanly [call_1] [call_2].";
        String conclusion = "The slope at the crossover is the derivative dy/dx of the response curve.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, conclusion);
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:derivative"));
    }

    // ── True negatives: cited claims that should NOT flag ───────────────────

    @Test
    void derivativeWithSameSentenceCallRef_passes() {
        String findings = "The derivative dy/dx at the crossover, computed via "
                + "[call_2], is -0.043.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:derivative"));
    }

    @Test
    void uncertaintyWithSameSentenceCallRef_passes() {
        String findings = "The uncertainty band [call_3] is ±0.02 at the 95% CI.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .noneMatch(r -> r.startsWith("unsourced_claim:uncertainty"));
    }

    @Test
    void p595_basquinCleanProse_passes() {
        // P-595-shape: every numeric claim is paired with [call_N] in the
        // same sentence. Must not FP.
        String findings = "Call 1 [call_1] returned the fatigue exponent b=0.12. "
                + "The Basquin coefficient measured by [call_2] is σ_f'=1450 MPa.";
        String conclusion = "The hypothesis is supported by [call_1] and [call_2].";
        assertThat(UnsourcedClaimValidator.validate(findings, conclusion)).isEmpty();
    }

    // ── Scope boundaries: sample-size claims belong to a different validator ─

    @Test
    void p605_sampleSizeClaim_NOTflaggedByThisValidator() {
        // P-605 Kepler: "53 samples" / "20 samples" inflation is
        // SinglePointSweepValidator's job. UnsourcedClaimValidator must
        // not double-stamp the sample-size pattern.
        String findings = "Across 53 samples the mean deviation is 4.12%. "
                + "Across 20 samples the deviation is 4.08%.";
        // No derivative/uncertainty/error/±N triggers in the prose, so
        // this validator should return empty (even though the prose IS
        // fabricated by some other measure).
        assertThat(UnsourcedClaimValidator.validate(findings, "")).isEmpty();
    }

    // ── Null / empty robustness ─────────────────────────────────────────────

    @Test
    void emptyInputs_returnEmpty() {
        assertThat(UnsourcedClaimValidator.validate(Map.of(), "")).isEmpty();
        assertThat(UnsourcedClaimValidator.validate((String) null, null)).isEmpty();
        assertThat(UnsourcedClaimValidator.validate("", "")).isEmpty();
    }

    @Test
    void multipleClaimsOfSameType_collapseToOneReason() {
        // Two derivative claims in different sentences should fold into
        // a single unsourced_claim:derivative reason — the call_path
        // stamp must stay compact.
        String findings = "The derivative is steep across the regime. "
                + "Another derivative measure is moderate.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        long derivCount = reasons.stream()
                .filter(r -> r.startsWith("unsourced_claim:derivative")).count();
        assertThat(derivCount).isEqualTo(1);
    }

    @Test
    void claimTypesFoldIndependently() {
        // One sentence per claim type, all unsourced — each type produces
        // exactly one reason.
        String findings = "The derivative is steep. "
                + "The uncertainty is large. "
                + "The maximum observed error is 1.5%.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).hasSize(3);
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:derivative"));
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:uncertainty"));
        assertThat(reasons).anyMatch(r -> r.startsWith("unsourced_claim:error"));
    }

    // ── Absence/meta exemption (2026-06-06 review rec #2) ──────────────────

    @Test
    void p214_absenceOfUncertaintyAnalysis_exempt() {
        // P-b72c-214's struck sentence — a negation asserting the ABSENCE of an
        // uncertainty analysis. Nothing to cite; flagging it punishes honest hedging.
        String findings = "No fitting procedure, confidence interval, or standard "
                + "error on this intercept was produced by the available tools; no "
                + "uncertainty analysis is therefore reported, and the value should "
                + "be understood as an empirically derived upper bound.";
        assertThat(UnsourcedClaimValidator.validate(findings, "")).isEmpty();
    }

    @Test
    void negatedSentenceWithConcreteValue_stillFlagged() {
        // A digit means a concrete claim is being made — the exemption must not
        // launder "error no greater than 0.5" style sentences.
        String findings = "The uncertainty is no greater than 0.5 degrees at the crossover.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .anyMatch(r -> r.startsWith("unsourced_claim:uncertainty"));
    }

    @Test
    void positiveUncertaintyProseWithoutNegation_stillFlagged() {
        // No negation cue → exemption does not apply (pre-existing behavior kept).
        String findings = "The uncertainty grows sharply across the regime.";
        assertThat(UnsourcedClaimValidator.validate(findings, ""))
                .anyMatch(r -> r.startsWith("unsourced_claim:uncertainty"));
    }

    @Test
    void p239_absenceNoun_alsoExempt() {
        // P-b72c-239: "Given the wide relative uncertainty and the absence of
        // finer-grained scans…" — hedging flagged because the cue list had
        // only the adjective "absent", not the noun "absence".
        String findings = "Given the wide relative uncertainty and the absence of "
                + "finer-grained scans, no stronger claim is made.";
        assertThat(UnsourcedClaimValidator.validate(findings, "")).isEmpty();
    }

    // ── Flagged-sentence snippet in the reason (2026-06-06 rec #2) ──────────

    @Test
    void reasonCarriesFlaggedSentenceSnippet() {
        // P-b72c-219: the downgrade rewrote Findings before publication, so the
        // strike was unclassifiable post-hoc. The reason must quote the sentence.
        String findings = "The uncertainty band spans 0.3 units at the boundary.";
        List<String> reasons = UnsourcedClaimValidator.validate(findings, "");
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .startsWith("unsourced_claim:uncertainty")   // stamp token intact
                .contains("Sentence: \"The uncertainty band spans 0.3 units");
    }

    @Test
    void snippetTruncatesLongSentencesAt60Chars() {
        String longSentence = "The uncertainty in this extended discussion of the "
                + "propagated error term is 0.5 across every window we examined "
                + "in the full sweep.";
        List<String> reasons = UnsourcedClaimValidator.validate(longSentence, "");
        assertThat(reasons).hasSize(1);
        String quoted = reasons.get(0).substring(reasons.get(0).indexOf("Sentence: \"") + 11);
        assertThat(quoted).hasSizeLessThanOrEqualTo(63);   // 60 + ellipsis + closing quote
        assertThat(quoted).contains("…");
    }
}
