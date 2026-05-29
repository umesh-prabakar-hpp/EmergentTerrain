package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-29 — pins {@link ReviewState}, the pipeline/quality-health axis added
 * to disentangle the overloaded {@code status} string ({@code NEEDS_REVISION}
 * conflating incomplete-but-sound with defective; {@code NULL_RESULT} conflating
 * genuine null with gate-flagged). Pure enum → no Mockito, runs everywhere.
 */
class ReviewStateTest {

    // ---- isReworkCandidate: only INCOMPLETE is a re-queue candidate ----

    @Test
    void onlyIncompleteIsAReworkCandidate() {
        assertThat(ReviewState.INCOMPLETE.isReworkCandidate()).isTrue();
        assertThat(ReviewState.CLEAN.isReworkCandidate()).isFalse();
        assertThat(ReviewState.REVISABLE_DEFECT.isReworkCandidate()).isFalse();
        assertThat(ReviewState.GATE_FLAGGED.isReworkCandidate()).isFalse();
    }

    // ---- forNull: genuine null is CLEAN, gate-forced null is GATE_FLAGGED ----

    @Test
    void genuineNullIsClean() {
        // The science genuinely came back null — a real, clean outcome.
        assertThat(ReviewState.forNull(false)).isEqualTo(ReviewState.CLEAN);
    }

    @Test
    void gateForcedNullIsFlagged() {
        // numericInconsistentDowngraded forced the null — the harness
        // false-positive population (the rec #1 reconcile target).
        assertThat(ReviewState.forNull(true)).isEqualTo(ReviewState.GATE_FLAGGED);
    }

    // ---- forPanelDemotion: degraded panel is INCOMPLETE, grade-floor is a defect ----

    @Test
    void degradedPanelDemotionIsIncomplete() {
        // Fewer reviewers ran than configured — the review didn't fully happen.
        String callPath = "narr → panel:n=2:degraded:demoted_to_needs_revision";
        assertThat(ReviewState.forPanelDemotion(callPath)).isEqualTo(ReviewState.INCOMPLETE);
    }

    @Test
    void gradeFloorDemotionIsRevisableDefect() {
        // A full panel graded the content below the floor — a real quality signal.
        String callPath = "narr → panel:grade_floor=0.23:demoted_to_needs_revision";
        assertThat(ReviewState.forPanelDemotion(callPath)).isEqualTo(ReviewState.REVISABLE_DEFECT);
    }

    @Test
    void panelDemotionNullCallPathDefaultsToRevisableDefect() {
        assertThat(ReviewState.forPanelDemotion(null)).isEqualTo(ReviewState.REVISABLE_DEFECT);
    }

    // ---- enum is stable for persistence (name() round-trip) ----

    @Test
    void enumNamesAreStableForPersistence() {
        // reviewState is persisted as name(); valueOf must round-trip.
        for (ReviewState s : ReviewState.values()) {
            assertThat(ReviewState.valueOf(s.name())).isEqualTo(s);
        }
        assertThat(ReviewState.values())
                .containsExactly(ReviewState.CLEAN, ReviewState.INCOMPLETE,
                        ReviewState.REVISABLE_DEFECT, ReviewState.GATE_FLAGGED);
    }
}
