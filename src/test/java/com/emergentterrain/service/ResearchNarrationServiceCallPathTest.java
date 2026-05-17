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
}
