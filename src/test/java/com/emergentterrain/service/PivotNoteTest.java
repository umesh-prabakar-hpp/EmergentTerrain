package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 32 Wave 2 (32.3) — the corrective-re-run note. The loop's trigger and
 * backstop are pinned elsewhere ({@code ScoreCalibrationTest}'s
 * hypothesisLhsUncovered fixtures; the publish gate); this pins the prompt
 * contract: name the preregistered lhs, mandate one on-target call, and do
 * NOT forbid extra exploration (P-b72c-222's regime probing stays welcome).
 */
class PivotNoteTest {

    @Test
    void noteNamesLhsAndMandatesOnTargetCall() {
        String note = ResearchNarrationService.buildPivotNote("max_prime_gap");
        assertThat(note)
                .contains("PRE-REGISTERED lhs 'max_prime_gap'")
                .contains("lhs=\"max_prime_gap\"")
                .contains("MUST use");
    }

    @Test
    void noteDoesNotForbidAdditionalExploration() {
        String note = ResearchNarrationService.buildPivotNote("cramer_gap_ratio");
        assertThat(note).contains("remain welcome");
        assertThat(note).doesNotContain("only").doesNotContain("ONLY");
    }
}
