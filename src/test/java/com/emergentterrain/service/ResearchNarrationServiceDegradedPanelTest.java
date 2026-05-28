package com.emergentterrain.service;

import com.emergentterrain.research.PanelOutcome;
import com.emergentterrain.research.PanelReview;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 D4 relaxation (research/a3289005 review rec #7) — a degraded panel
 * (n ∈ {2,3} of configured 4) should keep PUBLISHED when it converged strongly
 * AND cleared the grade floor, instead of being demoted purely on reviewer
 * count. The count shortfall was an infrastructure artifact (providers failing
 * to seat reviewers), not a quality signal.
 */
class ResearchNarrationServiceDegradedPanelTest {

    private static final double AGREEMENT_FLOOR = 0.80;
    private static final double GRADE_FLOOR     = 0.30;

    private static PanelOutcome outcome(double agreement, double h, double c, double r) {
        PanelReview.Grades g = new PanelReview.Grades(h, c, r, 0.0);
        PanelReview dummy = new PanelReview(
                "rev", g, List.of(), List.of(), List.of(), List.of(), Optional.empty());
        return new PanelOutcome(g, agreement,
                List.of(), List.of(), List.of(), List.of(), List.of(dummy, dummy));
    }

    @Test
    void highAgreement_goodGrades_keepsPublished() {
        // The rec #7 case: only 2 of 4 reviewers seated, but they converged
        // (agreement 0.95) and graded the paper well — keep PUBLISHED.
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                outcome(0.95, 0.6, 0.6, 0.6), AGREEMENT_FLOOR, GRADE_FLOOR)).isTrue();
    }

    @Test
    void highAgreement_lowGrades_stillDemotes() {
        // Reviewers agreed the paper is weak — count relaxation must NOT rescue it.
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                outcome(0.95, 0.05, 0.05, 0.05), AGREEMENT_FLOOR, GRADE_FLOOR)).isFalse();
    }

    @Test
    void lowAgreement_goodGrades_stillDemotes() {
        // The small panel diverged — its signal is not informative enough to trust.
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                outcome(0.50, 0.6, 0.6, 0.6), AGREEMENT_FLOOR, GRADE_FLOOR)).isFalse();
    }

    @Test
    void boundariesAreInclusive() {
        // agreement == floor and grades == floor both clear (>=, not >).
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                outcome(0.80, 0.30, 0.30, 0.30), AGREEMENT_FLOOR, GRADE_FLOOR)).isTrue();
    }

    @Test
    void emptyPanel_neverCleared() {
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                PanelOutcome.empty(), AGREEMENT_FLOOR, GRADE_FLOOR)).isFalse();
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                null, AGREEMENT_FLOOR, GRADE_FLOOR)).isFalse();
    }

    @Test
    void agreementFloorAboveOne_restoresAlwaysDemote() {
        // Escape hatch: a floor > 1.0 makes the clause unreachable (agreement
        // can never exceed 1.0), so every degraded panel demotes as before.
        assertThat(ResearchNarrationService.degradedPanelClearedForPublish(
                outcome(1.0, 0.9, 0.9, 0.9), 2.0, GRADE_FLOOR)).isFalse();
    }
}
