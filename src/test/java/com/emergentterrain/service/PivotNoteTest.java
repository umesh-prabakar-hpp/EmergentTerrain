package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PredicateStudyPreRegistration;

import java.util.List;

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
    void twoTermTightenIsExplicitlyASensitivityAndKeepsTheRegisteredTest() {
        String note = ResearchNarrationService.buildTwoTermTightenNote("twin_prime_density",
                new com.emergentterrain.research.ScoreCalibration.TwoTermTightenSuggestion(
                        "mean_divisor_excess", "max_divisor_count", "1.997", "2", "-386.2461",
                        "1500123", "5000", "20"));

        assertThat(note).contains("SENSITIVITY ANALYSIS")
                .contains("not a replacement for the registered hypothesis")
                .contains("window_start=1500123");
    }

    @Test
    void correctiveTraceIsAppendedRatherThanReplacingInitialEvidence() {
        ToolInvocation initial = new ToolInvocation("verify_conjecture", "{}", "HELD", 0, "call_1");
        ToolInvocation corrective = new ToolInvocation("verify_conjecture", "{}", "REFUTED", 1, "call_2");

        assertThat(ResearchNarrationService.appendTrace(List.of(initial), List.of(corrective)))
                .containsExactly(initial, corrective);
        assertThat(ResearchNarrationService.shiftCallMarkers("Result [call_1]", 1))
                .isEqualTo("Result [call_2]");
    }

    @Test
    void noteNamesLhsAndMandatesOnTargetCall() {
        String note = ResearchNarrationService.buildPivotNote("max_prime_gap");
        assertThat(note)
                .contains("PRE-REGISTERED lhs 'max_prime_gap'")
                .contains("lhs=\"max_prime_gap\"")
                .contains("MUST use");
    }

    @Test
    void baselineSensitivityNoteRequiresDisjointWidthsAndAnIndependentLocation() {
        PaperDraft draft = new PaperDraft(null, "bot", "MATHEMATICS", "density", "title",
                "Exact census baseline 0.220", "Baseline 0.220", "", "EMPIRICAL", List.of(),
                0.5, 0.5, 0, 0, java.time.Instant.now(), 1, "P-test", null, null, false,
                null, null, List.of(), null, null,
                com.emergentterrain.research.ResultClassification.SUPPORTED, null);
        assertThat(ResearchNarrationService.buildPredicateBaselineSensitivityNote(draft))
                .contains("two distinct window widths").contains("pairwise-disjoint ranges");
    }

    @Test
    void predicateStudyPreRegistrationNoteNamesTheLockedWindows() {
        var registration = PredicateStudyPreRegistration.from(new DynamicProblem(
                "d-1", "abundant finite density", 350, 850,
                "isAbundant", "fraction_satisfying", 0.22, "Finite comparison.",
                DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL, 0.03));

        assertThat(ResearchNarrationService.buildPredicateStudyPreRegistrationNote(registration))
                .contains("PREDICATE-STUDY PRE-REGISTRATION NOTE")
                .contains("baseline=0.22").contains("[350,850)").contains("[1350,1850)")
                .contains("call search_literature").contains("stipulated finite-window calibration");
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

    // ── 2026-06-14 — Change 4: reviewer classification corrected on a conclusion inversion ──

    @Test
    void classificationLineCorrectedWhenSupportedSeedIsInverted() {
        // inverted SUPPORTED → the reviewer is steered to the actual finding, NOT told "SUPPORTED".
        String corrected = ResearchNarrationService.classificationLineText("SUPPORTED", true);
        org.assertj.core.api.Assertions.assertThat(corrected)
                .contains("INVERTS")
                .contains("ACTUAL finding")
                .doesNotContain("Result classification: SUPPORTED\n");
        // not inverted → the stored label verbatim (legacy).
        org.assertj.core.api.Assertions.assertThat(
                ResearchNarrationService.classificationLineText("SUPPORTED", false))
                .isEqualTo("Result classification: SUPPORTED\n");
        org.assertj.core.api.Assertions.assertThat(
                ResearchNarrationService.classificationLineText("NULL_RESULT", false))
                .isEqualTo("Result classification: NULL_RESULT\n");
    }

    // ── 2026-06-14 — tight-k re-run note: enforce the tool's own tight-k suggestion ──

    @Test
    void tightenNoteTargetsFailureRegionWindowsAndClimbsK() {
        String note = ResearchNarrationService.buildTightenNote("max_prime_gap",
                new com.emergentterrain.research.ScoreCalibration.TightenSuggestion(
                        "log_sq_window_start", "0", "0.4880", "300000", "1000", "50"));
        org.assertj.core.api.Assertions.assertThat(note)
                .contains("'max_prime_gap'")
                .contains("FAILURE REGION")
                .contains("window_start=300000")
                .contains("window_size=1000")
                .contains("num_windows=50")
                .contains("rhs=\"log_sq_window_start\"")
                .contains("INCREASE k")           // climb k upward, not pin the benign-region value
                .contains("0.4880");
    }

    @Test
    void tightenNoteFallsBackWhenNoFailureWindowsCaptured() {
        String note = ResearchNarrationService.buildTightenNote("max_prime_gap",
                new com.emergentterrain.research.ScoreCalibration.TightenSuggestion(
                        null, null, "0.5", null, null, null));
        org.assertj.core.api.Assertions.assertThat(note)
                .contains("k≈0.5").contains("flat bound REFUTED")
                .doesNotContain("window_start=").doesNotContain("rhs=");
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
