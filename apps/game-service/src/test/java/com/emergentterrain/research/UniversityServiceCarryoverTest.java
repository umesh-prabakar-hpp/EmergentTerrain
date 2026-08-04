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

    // ---- 2026-05-29 review §1 rec #4: citation hard-cap (trace.size strip) ----

    @Test
    void stripsCallMarkersBeyondTraceSize() {
        // P-a328-993 signature: cites call_18/19 on a ~5-call paper. Out-of-range
        // markers are dropped; the number stays.
        String prose = "The crossover is ~4 atm [call_3] and the peak 0.499% [call_18].";
        String out = UniversityService.stripOutOfRangeCallMarkers(prose, 5);
        assertThat(out).contains("[call_3]");          // in range → kept
        assertThat(out).doesNotContain("[call_18]");    // beyond trace → stripped
        assertThat(out).contains("0.499%");             // the number survives
    }

    @Test
    void keepsAllMarkersWhenAllInRange() {
        String prose = "a [call_1] b [call_2] c [call_5].";
        assertThat(UniversityService.stripOutOfRangeCallMarkers(prose, 5)).isEqualTo(prose);
    }

    @Test
    void emptyTraceStripsEveryCitation() {
        // No tool calls ran → any [call_N] is fabricated.
        String prose = "Result 42 [call_1] confirmed [call_2].";
        String out = UniversityService.stripOutOfRangeCallMarkers(prose, 0);
        assertThat(out).doesNotContain("[call_");
        assertThat(out).contains("Result 42").contains("confirmed");
    }

    @Test
    void stripsZeroIndexedAndLeadingSpace() {
        // call_0 is invalid (1-based); the leading space is removed too so no
        // double-space is left behind.
        String out = UniversityService.stripOutOfRangeCallMarkers("value 5.2 [call_0] holds", 3);
        assertThat(out).isEqualTo("value 5.2 holds");
    }

    @Test
    void stripAcrossFindingsBlocks() {
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "block one [call_2] ok");
        blocks.put(2, "block two [call_9] bad");
        java.util.Map<Integer, String> out =
                UniversityService.stripOutOfRangeCallMarkers(blocks, 3);
        assertThat(out.get(1)).contains("[call_2]");
        assertThat(out.get(2)).doesNotContain("[call_9]");
    }

    @Test
    void stripHandlesNullAndEmpty() {
        assertThat(UniversityService.stripOutOfRangeCallMarkers((String) null, 3)).isNull();
        assertThat(UniversityService.stripOutOfRangeCallMarkers("", 3)).isEmpty();
        assertThat(UniversityService.stripOutOfRangeCallMarkers("no markers here", 3))
                .isEqualTo("no markers here");
    }
}
