package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rec #9 gate-attribution — pins {@link UniversityService#attributedGates},
 * which extracts the demotion / audit gate stamps from a fully-assembled call
 * path so a non-clean terminal status can be attributed to the specific gate(s)
 * that fired. Several gates frequently overlap on one paper (the a3289005 batch
 * showed citation-downgrade + panel-degrade + grade-floor + a publish-ladder
 * gate all on the same paper), masking the load-bearing cause in aggregate logs.
 */
class UniversityServiceGateAttributionTest {

    @Test
    void capturesEachDemotionGateOnAnOverlappingPath() {
        // P-a328-984 shape: four gates fire on one paper.
        String path = "fp:claude-sonnet-4-6 → prof:claude-sonnet-4-6:ok → "
                + "citations:downgrade→null_result → panel:n=3:degraded:from=4 → "
                + "panel:n=3:degraded:demoted_to_needs_revision → "
                + "panel:grade_floor=0.27:demoted_to_needs_revision → panel_revision:ok → "
                + "publish:unsourced_claim:uncertainty→needs_revision";
        List<String> gates = UniversityService.attributedGates(path);
        assertThat(gates).containsExactly(
                "citations:downgrade→null_result",
                "panel:n=3:degraded:demoted_to_needs_revision",
                "panel:grade_floor=0.27:demoted_to_needs_revision",
                "publish:unsourced_claim:uncertainty→needs_revision");
    }

    @Test
    void doesNotCaptureInformationalSegments() {
        // The "panel:n=3:degraded:from=4" stamp and the ":ok" stamps are not gates.
        String path = "fp:m → prof:m:ok → panel:n=3:degraded:from=4 → panel_revision:ok";
        assertThat(UniversityService.attributedGates(path)).isEmpty();
    }

    @Test
    void cleanPublishedPath_noGates() {
        String path = "fp:m → prof:m:ok → rev:m:ok → citations:uncited=0 → panel:n=4:agreement=0.79";
        assertThat(UniversityService.attributedGates(path)).isEmpty();
    }

    @Test
    void capturesSuppressionRejectionAndIncompleteMarkers() {
        assertThat(UniversityService.attributedGates("fp:m → critique:skip:incomplete_firstpass"))
                .containsExactly("critique:skip:incomplete_firstpass");
        assertThat(UniversityService.attributedGates("fp:m → prof:error→fp"))
                .containsExactly("prof:error→fp");
        assertThat(UniversityService.attributedGates("fp:m → numeric_inconsistent→suppressed"))
                .containsExactly("numeric_inconsistent→suppressed");
        assertThat(UniversityService.attributedGates("fp:m → audit:physical_mechanism→REJECTED"))
                .containsExactly("audit:physical_mechanism→REJECTED");
    }

    @Test
    void withinStampArrowNotSplit() {
        // "citations:downgrade→null_result" has an unspaced internal arrow; it must
        // survive as a single segment, not be split on the spaced " → " separator.
        assertThat(UniversityService.attributedGates("citations:downgrade→null_result"))
                .containsExactly("citations:downgrade→null_result");
    }

    @Test
    void capturesBindingRegimeCoverageGate() {
        // Room b72c4e10 Rec 3 — the binding-regime coverage stamp ends in
        // →needs_revision, so it is attributed like the other publish-ladder gates.
        String path = "fp:m → prof:m:ok → rev:m:ok → "
                + "publish:binding_regime_uncovered→needs_revision";
        assertThat(UniversityService.attributedGates(path))
                .containsExactly("publish:binding_regime_uncovered→needs_revision");
    }

    @Test
    void onlyCleanPublishedPapersMayPromoteFindingsAcrossRuns() {
        assertThat(UniversityService.permitsFindingsPromotion("PUBLISHED")).isTrue();
        assertThat(UniversityService.permitsFindingsPromotion("NEEDS_REVISION")).isFalse();
        assertThat(UniversityService.permitsFindingsPromotion("PUBLISHED_NO_PROFESSOR")).isFalse();
        assertThat(UniversityService.permitsFindingsPromotion("NULL_RESULT")).isFalse();
        assertThat(UniversityService.permitsFindingsPromotion("REJECTED")).isFalse();
    }

    @Test
    void nullOrBlankPath_empty() {
        assertThat(UniversityService.attributedGates(null)).isEmpty();
        assertThat(UniversityService.attributedGates("")).isEmpty();
        assertThat(UniversityService.attributedGates("   ")).isEmpty();
    }
}
