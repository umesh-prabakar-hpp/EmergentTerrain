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
}
