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

    // ── 2026-06-13 — second-order note: enforce engagement, leave the object to the model ──

    @Test
    void secondOrderNoteNamesLhsAndDemandsAFrameButNotAScalar() {
        String note = ResearchNarrationService.buildSecondOrderNote("mean_omega");
        assertThat(note)
                .contains("'mean_omega'")
                .contains("verify_constant")
                .contains("verify_oscillation")
                .contains("verify_growth")
                .contains("Choose the second-order object");   // determination stays with the model
        // It must NOT hardcode the specific scalar — that would hand-author the math insight.
        assertThat(note).doesNotContain("omega_ek_ratio").doesNotContain("omega_Omega_gap");
    }

    // ── 2026-06-13 — in-flight re-run gate (replaces the drain latch for re-runs) ──

    @Test
    void inFlightReRun_blockedOnlyAtHardStop() {
        // Hard stop → never (app terminating), regardless of draining/cap.
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(true, true, 2, 0)).isFalse();
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(true, false, 2, 0)).isFalse();
    }

    @Test
    void inFlightReRun_preDrainIsUnbounded() {
        // Not draining → always allowed (normal budget covers it; per-paper cap applies elsewhere).
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(false, false, 2, 99)).isTrue();
    }

    // ── 2026-06-14 — tight-k re-run note: enforce the tool's own tight-k suggestion ──

    @Test
    void tightenNoteNamesLhsRhsCAndTheToolSuggestedK() {
        String note = ResearchNarrationService.buildTightenNote("max_prime_gap",
                new com.emergentterrain.research.ScoreCalibration.TightenSuggestion(
                        "log_sq_window_start", "0", "0.4880"));
        org.assertj.core.api.Assertions.assertThat(note)
                .contains("'max_prime_gap'")
                .contains("k=0.4880")
                .contains("rhs=\"log_sq_window_start\"")
                .contains("c=0")
                .contains("TIGHT")
                .contains("failure region");
    }

    @Test
    void tightenNoteOmitsRhsAndCWhenAbsent() {
        String note = ResearchNarrationService.buildTightenNote("max_prime_gap",
                new com.emergentterrain.research.ScoreCalibration.TightenSuggestion(null, null, "0.5"));
        org.assertj.core.api.Assertions.assertThat(note)
                .contains("k=0.5").doesNotContain("rhs=").doesNotContain(", c=");
    }

    // ── 2026-06-13 — survivor-aware fallback guard: cite established constants ──

    @Test
    void establishedConstantNoteListsSurvivorsAndSaysCiteNotReMeasure() {
        String note = ResearchNarrationService.buildEstablishedConstantNote(
                java.util.List.of("mean_divisor_excess → 1.1544", "twin_vs_prime_sq → 1.3203"));
        org.assertj.core.api.Assertions.assertThat(note)
                .contains("mean_divisor_excess → 1.1544")
                .contains("twin_vs_prime_sq → 1.3203")
                .contains("CITE")
                .contains("re-measuring")
                .contains("OPEN or not-yet-established");
    }

    @Test
    void establishedConstantNoteIsEmptyWhenNoSurvivors() {
        org.assertj.core.api.Assertions.assertThat(
                ResearchNarrationService.buildEstablishedConstantNote(java.util.List.of())).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                ResearchNarrationService.buildEstablishedConstantNote(null)).isEmpty();
    }

    @Test
    void inFlightReRun_whileDrainingIsCappedByReserve() {
        // Draining → allowed under the cap, blocked at/after it (protects the reserve).
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(false, true, 2, 0)).isTrue();
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(false, true, 2, 1)).isTrue();
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(false, true, 2, 2)).isFalse();
        // cap <= 0 disables the cap (still allowed while draining).
        assertThat(ResearchNarrationService.pivotRerunAllowedInFlight(false, true, 0, 99)).isTrue();
    }
}
