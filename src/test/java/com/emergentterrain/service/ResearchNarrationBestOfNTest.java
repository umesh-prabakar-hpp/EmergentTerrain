package com.emergentterrain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PanelOutcome;
import com.emergentterrain.research.PanelReview;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 29 §29.1.1 — unit tests for the best-of-N selection key
 * {@link ResearchNarrationService#scoreCandidate(NarrationResult)}. The driver
 * loop ({@code produceBestCandidate}) is integration-level (it runs full
 * narration cycles); here we pin only the pure scoring/ranking contract that
 * decides which candidate wins.
 */
class ResearchNarrationBestOfNTest {

    /** Non-suppressed candidate carrying a given self-confidence and panel. */
    private static NarrationResult candidate(double confidence, PanelOutcome panel) {
        return new NarrationResult(
                "hypothesis", null, "conclusion",   // hypothesis, findingsMap, conclusion
                null, "model-x",                     // followUp, aiModel
                confidence, List.of(),               // confidenceScore, citedPaperIds
                "fp:model-x", List.of(),             // callPath, typedCitations
                List.of(),                           // formalFindings
                "tmpl", "v1",                        // templateId, templateVersion
                null, null,                          // discussion, relatedWork
                List.of(),                           // auditGapDimensions
                List.of(),                           // toolCallTrace
                panel);                              // panelOutcome
    }

    /** A panel that ran (non-empty reviews) with explicit median grades + agreement. */
    private static PanelOutcome panel(double honesty, double completeness,
                                      double rigor, double novelty, double agreement) {
        PanelReview.Grades grades = new PanelReview.Grades(honesty, completeness, rigor, novelty);
        PanelReview review = new PanelReview("r1", grades,
                List.of(), List.of(), List.of(), List.of(), Optional.empty());
        return new PanelOutcome(grades, agreement,
                List.of(), List.of(), List.of(), List.of(), List.of(review));
    }

    @Test
    void suppressedCandidateScoresNegativeInfinity() {
        NarrationResult suppressed = NarrationResult.suppressed(
                NarrationResult.OutcomeStatus.BUDGET_EXHAUSTED,
                null, null, 0L, 0L);
        assertThat(suppressed.isSuppressed()).isTrue();
        assertThat(ResearchNarrationService.scoreCandidate(suppressed))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void nullCandidateScoresNegativeInfinity() {
        assertThat(ResearchNarrationService.scoreCandidate(null))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void panelRanScoreIsMeanOfFourAxesPlusAgreementTiebreak() {
        // grades 0.8/0.6/0.4/0.2 → composite 0.5; agreement 0.5 → +0.0005
        double score = ResearchNarrationService.scoreCandidate(
                candidate(0.99, panel(0.8, 0.6, 0.4, 0.2, 0.5)));
        assertThat(score).isCloseTo(0.5005, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void higherPanelGradesWin() {
        NarrationResult weak   = candidate(0.5, panel(0.4, 0.4, 0.4, 0.4, 1.0));
        NarrationResult strong = candidate(0.5, panel(0.9, 0.9, 0.9, 0.9, 1.0));
        assertThat(ResearchNarrationService.scoreCandidate(strong))
                .isGreaterThan(ResearchNarrationService.scoreCandidate(weak));
    }

    @Test
    void agreementBreaksTiesAtEqualGrades() {
        NarrationResult contested  = candidate(0.5, panel(0.7, 0.7, 0.7, 0.7, 0.2));
        NarrationResult unanimous  = candidate(0.5, panel(0.7, 0.7, 0.7, 0.7, 0.9));
        double contestedScore = ResearchNarrationService.scoreCandidate(contested);
        double unanimousScore = ResearchNarrationService.scoreCandidate(unanimous);
        assertThat(unanimousScore).isGreaterThan(contestedScore);
        // tiebreak is small: difference is 0.001 * (0.9 - 0.2) = 0.0007
        assertThat(unanimousScore - contestedScore)
                .isCloseTo(0.0007, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void panelOffFallsBackToConfidenceScore() {
        // null panel and empty panel both mean "panel did not run"
        assertThat(ResearchNarrationService.scoreCandidate(candidate(0.73, null)))
                .isEqualTo(0.73);
        assertThat(ResearchNarrationService.scoreCandidate(candidate(0.42, PanelOutcome.empty())))
                .isEqualTo(0.42);
    }

    @Test
    void aRealPanelCandidateAlwaysBeatsASuppressedOne() {
        NarrationResult suppressed = NarrationResult.suppressed(
                NarrationResult.OutcomeStatus.ERROR, null, null, 0L, 0L);
        // even a poorly-graded real candidate outranks a suppressed sentinel
        NarrationResult poorButReal = candidate(0.0, panel(0.05, 0.05, 0.05, 0.05, 0.0));
        assertThat(ResearchNarrationService.scoreCandidate(poorButReal))
                .isGreaterThan(ResearchNarrationService.scoreCandidate(suppressed));
    }
}
