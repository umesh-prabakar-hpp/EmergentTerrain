package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paper-review 2026-05-22 — unit tests for
 * {@link ResearchNarrationService#decideCitationGate}.
 *
 * <p>The pure-function router was extracted from
 * {@code applyCitationsGate} so the moderate-mismatch exemption can be
 * pinned without standing up the full service. The decision matrix:
 *
 * <pre>
 *   mode      | traceMismatch | uncitedHeavy | total ≤ T | trace>0 | decision
 *   --------- + ------------- + ------------ + --------- + ------- + ----------------------
 *   warn      | *             | *            | *         | *       | PUBLISH
 *   suppress  | true          | *            | *         | *       | SUPPRESS
 *   suppress  | false         | true         | *         | *       | SUPPRESS
 *   suppress  | false         | false        | *         | *       | PUBLISH
 *   downgrade | true          | *            | true      | true    | MODERATE_MISMATCH_RESCUE
 *   downgrade | true          | *            | false     | *       | NULL_RESULT_DOWNGRADE
 *   downgrade | true          | *            | true      | false   | NULL_RESULT_DOWNGRADE
 *   downgrade | false         | *            | *         | *       | MANUAL_REVIEW
 * </pre>
 */
class CitationGateDecisionTest {

    private static final int THRESHOLD = 5;  // matches yml default

    // ── warn mode — always PUBLISH ───────────────────────────────────────────

    @Test
    void warnMode_alwaysPublishes_regardlessOfFailures() {
        assertThat(ResearchNarrationService.decideCitationGate(
                /*uncited*/ 10, /*unmatched*/ 5, /*oot*/ 5, /*trace*/ 1, THRESHOLD, "warn"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.PUBLISH);
        assertThat(ResearchNarrationService.decideCitationGate(
                /*uncited*/ 0, /*unmatched*/ 0, /*oot*/ 0, /*trace*/ 0, THRESHOLD, "warn"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.PUBLISH);
    }

    @Test
    void unknownMode_defaultsToPublish() {
        // Defensive — a typo in app.research.citations-as-schema.mode falls
        // through to the safe behaviour, not the suppress path.
        assertThat(ResearchNarrationService.decideCitationGate(
                5, 5, 5, 1, THRESHOLD, "downgradde"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.PUBLISH);
        assertThat(ResearchNarrationService.decideCitationGate(
                5, 5, 5, 1, THRESHOLD, null))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.PUBLISH);
    }

    // ── suppress mode ────────────────────────────────────────────────────────

    @Test
    void suppressMode_dropsOnAnyTraceMismatch() {
        // unmatched > 0
        assertThat(ResearchNarrationService.decideCitationGate(
                0, 1, 0, 1, THRESHOLD, "suppress"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.SUPPRESS);
        // out_of_tolerance > 0
        assertThat(ResearchNarrationService.decideCitationGate(
                0, 0, 1, 1, THRESHOLD, "suppress"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.SUPPRESS);
    }

    @Test
    void suppressMode_dropsOnUncitedHeavy_even_withCleanTrace() {
        // uncited >= CITATIONS_UNCITED_SUPPRESS_THRESHOLD (3) on its own.
        assertThat(ResearchNarrationService.decideCitationGate(
                3, 0, 0, 1, THRESHOLD, "suppress"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.SUPPRESS);
    }

    @Test
    void suppressMode_publishesBelowThreshold() {
        // uncited < 3 AND no trace mismatch → publish.
        assertThat(ResearchNarrationService.decideCitationGate(
                2, 0, 0, 1, THRESHOLD, "suppress"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.PUBLISH);
    }

    // ── downgrade mode — exemption fires ─────────────────────────────────────

    @Test
    void downgradeMode_rescuesP_a328_905_pattern() {
        // P-a328-905: uncited=2, unmatched=2, out_of_tolerance=3, trace had
        // one successful predicate_scan call. Total mismatch = 5 = threshold.
        // Pre-fix: NULL_RESULT downgrade. Post-fix: MODERATE_MISMATCH_RESCUE.
        assertThat(ResearchNarrationService.decideCitationGate(
                2, 2, 3, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.MODERATE_MISMATCH_RESCUE);
    }

    @Test
    void downgradeMode_rescuesP_a328_900_pattern() {
        // P-a328-900: uncited=3, unmatched=3, out_of_tolerance=0, trace had
        // one /eval call. Total mismatch = 3 < threshold. Rescued.
        assertThat(ResearchNarrationService.decideCitationGate(
                3, 3, 0, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.MODERATE_MISMATCH_RESCUE);
    }

    @Test
    void downgradeMode_rescuesP_a328_901_pattern() {
        // P-a328-901: uncited=2, unmatched=5, out_of_tolerance=0, trace had
        // one /eval call. Total mismatch = 5 = threshold. Rescued.
        assertThat(ResearchNarrationService.decideCitationGate(
                2, 5, 0, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.MODERATE_MISMATCH_RESCUE);
    }

    // ── downgrade mode — exemption does NOT fire (still NULL_RESULT) ─────────

    @Test
    void downgradeMode_doesNotRescue_P_a328_902_pattern() {
        // P-a328-902: uncited=15, unmatched=7, out_of_tolerance=0. Total
        // mismatch = 7 > threshold. Stays on NULL_RESULT path — the prose
        // genuinely fabricated 7 numbers that the trace doesn't support.
        assertThat(ResearchNarrationService.decideCitationGate(
                15, 7, 0, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.NULL_RESULT_DOWNGRADE);
    }

    @Test
    void downgradeMode_doesNotRescue_emptyTrace() {
        // Even moderate mismatch is downgraded when the trace is empty —
        // there's no evidence to rescue against.
        assertThat(ResearchNarrationService.decideCitationGate(
                2, 2, 0, /*trace*/ 0, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.NULL_RESULT_DOWNGRADE);
    }

    @Test
    void downgradeMode_thresholdZero_disablesRescue() {
        // Operator setting app.citations.moderate-mismatch.threshold=0
        // restores the legacy NULL-on-any-mismatch behaviour.
        assertThat(ResearchNarrationService.decideCitationGate(
                2, 2, 0, 1, /*threshold*/ 0, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.NULL_RESULT_DOWNGRADE);
    }

    // ── downgrade mode — uncited-only ────────────────────────────────────────

    @Test
    void downgradeMode_uncitedOnly_routesToManualReview() {
        // Original behaviour preserved: when only uncited > 0 but the trace
        // matches, stamp MANUAL_REVIEW and publish.
        assertThat(ResearchNarrationService.decideCitationGate(
                5, 0, 0, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.MANUAL_REVIEW);
    }

    @Test
    void downgradeMode_zeroFailures_routesToManualReview() {
        // When there are zero failures, applyCitationsGate's caller short-
        // circuits to publish via the cv.hasFailures() check BEFORE this
        // decision function runs. The decision function only sees calls
        // that have at least some failure — but the contract still handles
        // zero gracefully (uncited=0 with no trace mismatch → MANUAL_REVIEW
        // path which is also a publish).
        assertThat(ResearchNarrationService.decideCitationGate(
                0, 0, 0, 1, THRESHOLD, "downgrade"))
                .isEqualTo(ResearchNarrationService.CitationGateDecision.MANUAL_REVIEW);
    }
}
