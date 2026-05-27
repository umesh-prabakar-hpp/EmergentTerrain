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

    // ---- selectBest: the winner-selection contract (the "candidates=3" smoke) ----

    @Test
    void selectBestPicksHighestGradedOfThree() {
        // Mirrors a candidates=3 run: three panel-scored candidates, the
        // best-graded one is selected for publish regardless of list order.
        NarrationResult weak    = candidate(0.5, panel(0.30, 0.30, 0.30, 0.30, 1.0));
        NarrationResult strong  = candidate(0.5, panel(0.90, 0.90, 0.90, 0.90, 1.0));
        NarrationResult middle  = candidate(0.5, panel(0.60, 0.60, 0.60, 0.60, 1.0));
        assertThat(ResearchNarrationService.selectBest(List.of(weak, strong, middle)))
                .isSameAs(strong);
        // order-independent
        assertThat(ResearchNarrationService.selectBest(List.of(strong, weak, middle)))
                .isSameAs(strong);
    }

    @Test
    void selectBestUsesPanelGradeNotConfidenceWhenPanelRan() {
        // A high-confidence but low-panel-grade candidate must LOSE to a
        // low-confidence high-panel-grade one — the panel is the fitness, not
        // the narrator's self-assessment.
        NarrationResult confidentButWeak = candidate(0.99, panel(0.20, 0.20, 0.20, 0.20, 1.0));
        NarrationResult humbleButStrong  = candidate(0.10, panel(0.85, 0.85, 0.85, 0.85, 1.0));
        assertThat(ResearchNarrationService.selectBest(List.of(confidentButWeak, humbleButStrong)))
                .isSameAs(humbleButStrong);
    }

    @Test
    void selectBestPrefersRealCandidateOverSuppressed() {
        NarrationResult suppressed = NarrationResult.suppressed(
                NarrationResult.OutcomeStatus.ERROR, null, null, 0L, 0L);
        NarrationResult real = candidate(0.5, panel(0.5, 0.5, 0.5, 0.5, 1.0));
        // suppressed produced first; the real candidate must still win
        assertThat(ResearchNarrationService.selectBest(List.of(suppressed, real)))
                .isSameAs(real);
    }

    @Test
    void selectBestAllSuppressedReturnsFirstSentinel() {
        // Degenerate run where every candidate suppressed — publish the first
        // sentinel (pre-29 publish-the-sentinel behaviour, queue counter still
        // decrements downstream).
        NarrationResult s1 = NarrationResult.suppressed(
                NarrationResult.OutcomeStatus.BUDGET_EXHAUSTED, null, null, 0L, 0L);
        NarrationResult s2 = NarrationResult.suppressed(
                NarrationResult.OutcomeStatus.ALL_MODELS_BLOCKED, null, null, 0L, 0L);
        assertThat(ResearchNarrationService.selectBest(List.of(s1, s2))).isSameAs(s1);
    }

    @Test
    void selectBestSingleAndEmpty() {
        NarrationResult only = candidate(0.7, null);
        assertThat(ResearchNarrationService.selectBest(List.of(only))).isSameAs(only);
        assertThat(ResearchNarrationService.selectBest(List.of())).isNull();
        assertThat(ResearchNarrationService.selectBest(null)).isNull();
    }

    // ---- §29.1.2 eval cascade: topM screen (which candidates get the panel) ----

    @Test
    void topMKeepsHighestScoredAndDropsTheRest() {
        // 4 first-pass-style screen values; top-2 by score are kept (the panel
        // would then run only on these 2, not all 4).
        List<Double> items = List.of(0.10, 0.90, 0.50, 0.70);
        List<Double> top2 = ResearchNarrationService.topM(items, d -> d, 2);
        assertThat(top2).containsExactly(0.90, 0.70);
    }

    @Test
    void topMStableOnTies() {
        // equal scores keep original relative order (deterministic screen)
        record Item(String id, double score) {}
        List<Item> items = List.of(
                new Item("a", 0.5), new Item("b", 0.5), new Item("c", 0.9), new Item("d", 0.5));
        List<Item> top3 = ResearchNarrationService.topM(items, Item::score, 3);
        assertThat(top3).extracting(Item::id).containsExactly("c", "a", "b");
    }

    @Test
    void topMEdgeCases() {
        List<Double> items = List.of(0.3, 0.1, 0.2);
        // m >= size → all, score-sorted descending
        assertThat(ResearchNarrationService.topM(items, d -> d, 9))
                .containsExactly(0.3, 0.2, 0.1);
        // m <= 0 → empty
        assertThat(ResearchNarrationService.topM(items, d -> d, 0)).isEmpty();
        // empty / null input → empty
        assertThat(ResearchNarrationService.topM(List.<Double>of(), d -> d, 2)).isEmpty();
        assertThat(ResearchNarrationService.topM((List<Double>) null, d -> d, 2)).isEmpty();
    }

    @Test
    void cascadeScreensCandidatesByFirstPassConfidence() {
        // The cascade screen ranks first-passes by scoreCandidate (confidence,
        // since a first-pass carries no panel yet). Confirm the highest-
        // confidence first-passes are the ones selected to be finished/paneled.
        NarrationResult fpLow  = candidate(0.20, null);
        NarrationResult fpMid  = candidate(0.55, null);
        NarrationResult fpHigh = candidate(0.95, null);
        List<NarrationResult> top2 = ResearchNarrationService.topM(
                List.of(fpLow, fpHigh, fpMid),
                ResearchNarrationService::scoreCandidate, 2);
        assertThat(top2).containsExactly(fpHigh, fpMid);
    }
}
