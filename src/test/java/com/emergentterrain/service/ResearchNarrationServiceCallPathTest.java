package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 F7.4 — call-path stamp format tests for the multi-reviewer panel.
 * Unit-tests the static {@code buildPanelCallPathStamp} helper directly so the
 * exact stamp format is pinned without standing up a full ResearchNarrationService.
 */
class ResearchNarrationServiceCallPathTest {

    @Test
    void fullPanelStampsAgreementOnly() {
        // 4 reviewers responded out of 4 configured — full panel.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(4, 4, 0.87);
        assertThat(stamp).isEqualTo("panel:n=4:agreement=0.87");
        assertThat(stamp).doesNotContain("degraded");
    }

    @Test
    void partialPanelStampsDegradedWithConfiguredCount() {
        // 2 of 4 responded — degraded.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(2, 4, 0.75);
        assertThat(stamp).isEqualTo("panel:n=2:degraded:from=4:agreement=0.75");
    }

    @Test
    void singleReviewerOutOfFourIsDegraded() {
        // 1 of 4 — operators can grep `degraded` to find these.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(1, 4, 1.0);
        assertThat(stamp).isEqualTo("panel:n=1:degraded:from=4:agreement=1.00");
    }

    @Test
    void singleConfiguredReviewerIsFullNotDegraded() {
        // panelSize=1 (only one provider opted in); 1 responded — full, not degraded.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(1, 1, 1.0);
        assertThat(stamp).isEqualTo("panel:n=1:agreement=1.00");
        assertThat(stamp).doesNotContain("degraded");
    }

    @Test
    void agreementScoreFormattedToTwoDecimalPlaces() {
        // 0.876543 must render as "0.88" (not "0.88...").
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(3, 3, 0.876543);
        assertThat(stamp).isEqualTo("panel:n=3:agreement=0.88");
    }

    // ---- Phase 14 F2b: professor-exception zero-successes suppression ----

    @Test
    void f2_zeroSuccessesAndMultiBlockSuppressesOnException() {
        // P-505-class signature: 0 successful tool calls, multi-block firstPass.
        // Suppress instead of falling through to firstPass-fallback so the
        // un-validated fabricated prose never reaches publishPaper.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 5)).isTrue();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 2)).isTrue();
    }

    @Test
    void f2_zeroSuccessesAndSingleBlockDoesNotSuppress() {
        // Single-block firstPass on exception is structurally indistinguishable
        // from a legitimate single-computation narration that lost its trace
        // to the exception; falling through to firstPass-fallback is safer
        // than suppressing.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 1)).isFalse();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 0)).isFalse();
    }

    @Test
    void f2_nonZeroSuccessesNeverSuppresses() {
        // The metrics gate at line 492 already handles the case where the
        // professor produced *some* successful calls. F2b's check fires
        // ONLY when no successes accumulated before the exception.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(1, 5)).isFalse();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(2, 5)).isFalse();
    }

    // ---- Phase 17 D3-ext: gate-skipped-professor quantitative-claim detector ----

    @Test
    void d3ext_p559FabricatedPendulumAngleDetected() {
        // Real conclusion text from P-a328-559 (gate:resources:professor case):
        // "the 1% error threshold occurs at about 25°" — no computation backs it.
        String conclusion = "The pre-registered prediction that the critical angle "
                + "θ_c would be less than 20° was not confirmed, as the 1% error "
                + "threshold occurs at about 25°.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_p568FabricatedBuoyantForceDetected() {
        // Real conclusion text from P-a328-568:
        // "maximum buoyant force of 1480 kN at a volume of 25.8 m³ and a
        //  liquid density of 5844 kg/m³"
        String conclusion = "The pre-registered prediction was supported by the "
                + "computation results, which showed a maximum buoyant force of "
                + "1480 kN at a volume of 25.8 m³ and a liquid density of "
                + "5844 kg/m³.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_p575InternalContradictionDetected() {
        // Real conclusion text from P-a328-575 (NULL_RESULT but claims "confirmed"):
        // "worst deviation being approximately 0.0005"
        String conclusion = "The pre-registered prediction that the ratio of the "
                + "truncated ζ-sum to the Euler product would remain within 5% of "
                + "unity was confirmed, with the worst deviation being "
                + "approximately 0.0005.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_honestQualitativeConclusionPasses() {
        // The intended honest shape for a gate-killed paper: no specific number.
        String conclusion = "The hypothesis remains untested in this submission. "
                + "The professor stage was skipped due to resource pressure and "
                + "no quantitative claim is made.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isFalse();
    }

    @Test
    void d3ext_callCitationsAloneDoNotTrigger() {
        // A conclusion that references prior calls but makes no quantitative
        // claim of its own. The [call_N] tokens carry digits we strip first.
        String conclusion = "The findings reported in [call_1] and [call_2] are "
                + "consistent with the hypothesis. See Computation 1 and "
                + "Computation 2 for details.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isFalse();
    }

    @Test
    void d3ext_nullAndBlankPass() {
        // Null and blank are both "no quantitative claim". Suppression should
        // NOT fire on these — they're handled upstream by the
        // fillFromTemplates / critique:skip:incomplete_firstpass path.
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(null)).isFalse();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim("")).isFalse();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim("   \n  ")).isFalse();
    }

    @Test
    void d3ext_digitOutsideStrippedRefsTriggers() {
        // A bare digit anywhere outside [call_N] / "Computation N" should
        // fire. The regex is intentionally loose — any specific number in a
        // gate-killed paper is suspect.
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(
                "[call_1] supports the result at threshold 0.05.")).isTrue();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(
                "Computation 1 yields a mean of 42.")).isTrue();
    }
}
