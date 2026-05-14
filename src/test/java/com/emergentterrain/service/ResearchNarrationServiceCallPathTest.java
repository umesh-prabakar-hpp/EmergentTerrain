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
}
