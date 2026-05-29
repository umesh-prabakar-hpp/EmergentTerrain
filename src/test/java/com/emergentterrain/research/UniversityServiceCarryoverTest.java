package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 hypothesis-carryover gate tests. Pins the status set that
 * propagates a paper's Conclusion to the author bot's
 * {@code researchHypothesis} field.
 *
 * <p>Targets the gate-skipped-professor failure where the first-pass
 * Conclusion is fabricated and, pre-gate, became the bot's next
 * hypothesis — a known propagation channel for fabricated content.
 */
class UniversityServiceCarryoverTest {

    @Test
    void publishedCarriesOver() {
        assertThat(UniversityService.isCarryoverAllowed("PUBLISHED")).isTrue();
    }

    @Test
    void publishedNoProfessorCarriesOver() {
        // The paper shipped its first-pass content but the professor stage
        // didn't run (commonly upstream quota exhaustion). The first-pass
        // narration is still considered the bot's working hypothesis.
        assertThat(UniversityService.isCarryoverAllowed("PUBLISHED_NO_PROFESSOR")).isTrue();
    }

    @Test
    void nullResultCarriesOver() {
        // NULL_RESULT means the computation ran and the hypothesis was *not*
        // supported. Carrying the conclusion forward is still meaningful — it
        // refines the bot's prior away from the disproved direction.
        assertThat(UniversityService.isCarryoverAllowed("NULL_RESULT")).isTrue();
    }

    @Test
    void needsRevisionBlocksCarryover() {
        // The fabrication carryover channel: a gate-skipped-professor paper
        // with a fabricated Conclusion must NOT seed the next experiment.
        assertThat(UniversityService.isCarryoverAllowed("NEEDS_REVISION")).isFalse();
    }

    @Test
    void rejectedBlocksCarryover() {
        // Validator-rejected paper has known structural defects (unphysical
        // magnitude, numerical inconsistency, cross-domain non-sequitur, etc.).
        // The conclusion is by definition not a trustworthy seed.
        assertThat(UniversityService.isCarryoverAllowed("REJECTED")).isFalse();
    }

    @Test
    void disputedBlocksCarryover() {
        // An independent replication produced a divergent result. Until the
        // dispute resolves, treating either side's conclusion as the new
        // hypothesis would steer the bot into the unresolved disagreement.
        assertThat(UniversityService.isCarryoverAllowed("DISPUTED")).isFalse();
    }

    @Test
    void unknownStatusBlocksCarryover() {
        // Default-deny: future statuses (e.g. SUPERSEDED, RETRACTED) are
        // blocked until explicitly allowlisted in the switch. Better to lose
        // a carryover than propagate from an unknown status.
        assertThat(UniversityService.isCarryoverAllowed("UNDER_REVIEW")).isFalse();
        assertThat(UniversityService.isCarryoverAllowed("SUPERSEDED")).isFalse();
    }

    @Test
    void nullStatusBlocksCarryover() {
        assertThat(UniversityService.isCarryoverAllowed(null)).isFalse();
    }

    @Test
    void emptyStatusBlocksCarryover() {
        assertThat(UniversityService.isCarryoverAllowed("")).isFalse();
    }

    // ---- 2026-05-29: INCOMPLETE re-queue score-improvement guard ----

    @Test
    void reworkThatFinishedSupersedesIncompleteEvenAtLowerScore() {
        // The whole point of the re-queue: a completed, peer-reviewed rework
        // replaces the unfinished original even if its score is lower — the
        // INCOMPLETE original's score was never reliably reviewed.
        assertThat(UniversityService.reworkIsImprovement(
                /* oldIncomplete */ true, /* newIncomplete */ false,
                /* oldScore */ 0.90, /* newScore */ 0.50)).isTrue();
    }

    @Test
    void stillIncompleteReworkReplacesOnlyWhenStrictlyHigherScore() {
        // Re-narration came out INCOMPLETE again (e.g. timed out once more).
        // Replace only if it at least scored higher; otherwise keep the original.
        assertThat(UniversityService.reworkIsImprovement(
                true, true, 0.40, 0.60)).isTrue();    // higher → replace
        assertThat(UniversityService.reworkIsImprovement(
                true, true, 0.60, 0.60)).isFalse();    // equal → keep original
        assertThat(UniversityService.reworkIsImprovement(
                true, true, 0.60, 0.40)).isFalse();    // lower → keep original
    }

    @Test
    void finishedReworkWithHigherScoreIsObviouslyAnImprovement() {
        assertThat(UniversityService.reworkIsImprovement(
                true, false, 0.30, 0.85)).isTrue();
    }

    @Test
    void bothCompleteRepublishNeedsStrictlyHigherScore() {
        // Defensive: if the original was somehow already complete, a re-publish
        // only wins on a strictly higher score (no free pass).
        assertThat(UniversityService.reworkIsImprovement(
                false, false, 0.70, 0.71)).isTrue();
        assertThat(UniversityService.reworkIsImprovement(
                false, false, 0.70, 0.70)).isFalse();
    }
}
