package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 7 F7.2 — deterministic aggregation tests. The aggregator is the load-bearing
 * step that turns N independent reviewer responses into one PanelOutcome the publish
 * path can persist. Each test pins one aggregation rule.
 */
class PanelOutcomeTest {

    @Test
    void emptyInputYieldsEmptyOutcome() {
        assertThat(PanelOutcome.aggregate(null).isEmpty()).isTrue();
        assertThat(PanelOutcome.aggregate(List.of()).isEmpty()).isTrue();
        assertThat(PanelOutcome.empty().isEmpty()).isTrue();
        assertThat(PanelOutcome.empty().agreementScore()).isEqualTo(1.0);
    }

    @Test
    void medianGradesAcrossOddNumberOfReviewers() {
        PanelReview a = review("a", 0.4, 0.5, 0.6, 0.3);
        PanelReview b = review("b", 0.6, 0.5, 0.7, 0.5);
        PanelReview c = review("c", 0.8, 0.5, 0.8, 0.9);
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b, c));
        // Sorted honestys [0.4, 0.6, 0.8] → median 0.6.
        assertThat(out.aggregateGrades().honesty()).isEqualTo(0.6, within(1e-9));
        assertThat(out.aggregateGrades().completeness()).isEqualTo(0.5, within(1e-9));
        assertThat(out.aggregateGrades().rigor()).isEqualTo(0.7, within(1e-9));
        assertThat(out.aggregateGrades().novelty()).isEqualTo(0.5, within(1e-9));
    }

    @Test
    void medianGradesAcrossEvenNumberOfReviewersAveragesMiddleTwo() {
        PanelReview a = review("a", 0.2, 0.0, 0.0, 0.0);
        PanelReview b = review("b", 0.4, 0.0, 0.0, 0.0);
        PanelReview c = review("c", 0.6, 0.0, 0.0, 0.0);
        PanelReview d = review("d", 0.8, 0.0, 0.0, 0.0);
        // Median of [0.2, 0.4, 0.6, 0.8] = (0.4 + 0.6) / 2 = 0.5.
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b, c, d));
        assertThat(out.aggregateGrades().honesty()).isEqualTo(0.5, within(1e-9));
    }

    @Test
    void agreementScoreIsHighWhenReviewersAgree() {
        PanelReview a = review("a", 0.8, 0.8, 0.8, 0.8);
        PanelReview b = review("b", 0.8, 0.8, 0.8, 0.8);
        PanelReview c = review("c", 0.8, 0.8, 0.8, 0.8);
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b, c));
        // Stddev is 0 across every axis → agreement is 1.0.
        assertThat(out.agreementScore()).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void agreementScoreDropsWhenReviewersDiverge() {
        PanelReview a = review("a", 0.1, 0.1, 0.1, 0.1);
        PanelReview b = review("b", 0.9, 0.9, 0.9, 0.9);
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b));
        // Mean = 0.5 on each axis; stddev = 0.4 on each axis.
        // agreement = 1 - 0.4 = 0.6.
        assertThat(out.agreementScore()).isEqualTo(0.6, within(1e-9));
    }

    @Test
    void singleReviewerYieldsAgreementOne() {
        PanelOutcome out = PanelOutcome.aggregate(List.of(review("solo", 0.5, 0.5, 0.5, 0.5)));
        // Stddev of one sample is 0; agreement reads as 1.0 by definition.
        assertThat(out.agreementScore()).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void consensusCitedCallsIsIntersectionAcrossAllReviewers() {
        PanelReview a = reviewWithCited("a", List.of(1, 2, 3, 5));
        PanelReview b = reviewWithCited("b", List.of(2, 3, 4, 5));
        PanelReview c = reviewWithCited("c", List.of(1, 3, 5, 7));
        // Intersection = {3, 5}; order preserved from the first reviewer.
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b, c));
        assertThat(out.consensusCitedCalls()).containsExactly(3, 5);
    }

    @Test
    void consensusCitedCallsEmptyWhenAnyReviewerDisagrees() {
        PanelReview a = reviewWithCited("a", List.of(1, 2));
        PanelReview b = reviewWithCited("b", List.of(3, 4));
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b));
        assertThat(out.consensusCitedCalls()).isEmpty();
    }

    @Test
    void consensusContradictionsRequireAtLeastTwoReviewers() {
        PanelReview a = reviewWithContradictions("a",
                new PanelReview.Contradiction(3, "sweep contradicts conclusion"));
        PanelReview b = reviewWithContradictions("b",
                new PanelReview.Contradiction(3, "different wording, same idx"));
        PanelReview c = reviewWithContradictions("c",
                new PanelReview.Contradiction(7, "lonely contradiction"));
        // trace_idx=3 flagged by 2 reviewers → kept; trace_idx=7 by 1 → dropped.
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b, c));
        assertThat(out.consensusContradictions()).hasSize(1);
        assertThat(out.consensusContradictions().get(0).traceIdx()).isEqualTo(3);
    }

    @Test
    void recommendationsAreDedupedUnion() {
        PanelReview a = reviewWithRecs("a", List.of("add boundary check", "test L=5mH"));
        PanelReview b = reviewWithRecs("b", List.of("test L=5mH", "add sensitivity sweep"));
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b));
        assertThat(out.recommendations()).containsExactly(
                "add boundary check", "test L=5mH", "add sensitivity sweep");
    }

    @Test
    void summaryReturnsDiagnosticMap() {
        PanelReview a = review("a", 0.8, 0.7, 0.9, 0.6);
        PanelReview b = review("b", 0.8, 0.7, 0.9, 0.6);
        PanelOutcome out = PanelOutcome.aggregate(List.of(a, b));
        java.util.Map<String, Object> summary = out.summary();
        assertThat(summary.get("reviewers")).isEqualTo(2);
        assertThat((double) summary.get("agreement")).isEqualTo(1.0, within(1e-9));
        assertThat((double) summary.get("median_honesty")).isEqualTo(0.8, within(1e-9));
    }

    // ---- Helpers ----

    private static PanelReview review(String id, double honesty, double completeness,
                                       double rigor, double novelty) {
        return new PanelReview(id,
                new PanelReview.Grades(honesty, completeness, rigor, novelty),
                List.of(), List.of(), List.of(), List.of());
    }

    private static PanelReview reviewWithCited(String id, List<Integer> cited) {
        return new PanelReview(id, new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                cited, List.of(), List.of(), List.of());
    }

    private static PanelReview reviewWithContradictions(String id,
                                                          PanelReview.Contradiction... cs) {
        return new PanelReview(id, new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                List.of(), List.of(), List.of(cs), List.of());
    }

    private static PanelReview reviewWithRecs(String id, List<String> recs) {
        return new PanelReview(id, new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                List.of(), List.of(), List.of(), recs);
    }
}
