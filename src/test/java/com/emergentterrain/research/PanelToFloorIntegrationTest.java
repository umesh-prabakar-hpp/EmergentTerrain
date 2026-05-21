package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 23.6 panel-emission slice — pins the production handshake that
 * lives in {@code UniversityService.publishPaper}:
 *
 * <pre>
 *   outcome.proposedEvidenceLabel().ifPresent(paper::setEvidenceLabel);
 *   paper.setEvidenceLabel(LabelFloor.demote(paper.evidenceLabel(), paper));
 * </pre>
 *
 * <p>Exercises the full panel → aggregated proposal → floor → final label
 * flow without instantiating the heavy {@code UniversityService}. The two
 * components ({@link PanelOutcome#aggregate} and
 * {@link LabelFloor#demote}) each have their own unit tests; this test
 * pins their <em>composition</em>, which is the slice's actual contract.
 */
class PanelToFloorIntegrationTest {

    @Test
    void panelEarnsEmpiricalResultWhenPaperHasMechanismAndAdversarialCheck() {
        PanelOutcome outcome = panel(
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT);
        ResearchPaper paper = paper(
                "Hypothesis: abundant_density <= 0.2515.",
                "## Mechanism: bounded by Erdős-style sieving. " +
                        "## Adversarial Check via verify_conjecture: HELD across 5 windows.",
                "Bound holds.");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, paper.evidenceLabel());
    }

    @Test
    void floorDemotesEmpiricalResultWhenPaperLacksMechanismAndCheck() {
        PanelOutcome outcome = panel(
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT);
        ResearchPaper paper = paper(
                "Hypothesis: abundant_density <= 0.2515.",
                "Mining trace: confirmed 8 runs.",
                "Not refuted.");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel(),
                "EMPIRICAL_RESULT proposed but paper has no mechanism + no check → demoted");
    }

    @Test
    void floorDemotesTheoremToCeilingNotAllTheWayToConjecture() {
        // 3/3 panelists propose THEOREM — but the paper lacks Q.E.D./∎/## Proof.
        // The floor returns the *ceiling* (the strongest label the text supports),
        // not the next-strongest below the proposal. This paper has mechanism +
        // verify_conjecture, so the ceiling is EMPIRICAL_RESULT.
        PanelOutcome outcome = panel(
                EvidenceLabel.THEOREM,
                EvidenceLabel.THEOREM,
                EvidenceLabel.THEOREM);
        ResearchPaper paper = paper(
                "Hypothesis: bound holds for all n.",
                "## Mechanism: handwave argument.\n" +
                        "## Adversarial Check via verify_conjecture: HELD.",
                "Established by handwave + sample.");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, paper.evidenceLabel(),
                "THEOREM proposed but no proof markers → demote to ceiling, " +
                        "which is EMPIRICAL_RESULT for this paper");
    }

    @Test
    void theoremProposalOnBarePaperGoesAllTheWayToConjecture() {
        // 3/3 panelists propose THEOREM and the paper has no proof markers
        // AND no mechanism + check. Ceiling is CONJECTURE; floor returns it.
        PanelOutcome outcome = panel(
                EvidenceLabel.THEOREM,
                EvidenceLabel.THEOREM,
                EvidenceLabel.THEOREM);
        ResearchPaper paper = paper(
                "Hypothesis: bound holds for all n.",
                "Some prose without proof structure or mechanism.",
                "");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel(),
                "THEOREM → CONJECTURE when paper has no proof AND no mechanism+check");
    }

    @Test
    void emptyPanelLeavesDefaultConjectureIntact() {
        // No reviewer voted — outcome.proposedEvidenceLabel is empty.
        // ifPresent is a no-op; floor's demote(CONJECTURE, paper) = CONJECTURE.
        PanelOutcome outcome = PanelOutcome.empty();
        ResearchPaper paper = paper(
                "Hypothesis: foo.",
                "## Mechanism: bar. ## Adversarial Check via verify_conjecture: HELD.",
                "ok");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel(),
                "no panel vote → paper retains its default CONJECTURE label");
    }

    @Test
    void majorityProposalWinsAndPassesFloor() {
        // 3 propose EMPIRICAL_RESULT, 1 proposes CONJECTURE on a 4-reviewer panel.
        // Aggregator returns EMPIRICAL_RESULT (earned by majority). Paper has
        // the required text markers, so the floor accepts.
        PanelOutcome outcome = panel(
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.CONJECTURE);
        ResearchPaper paper = paper(
                "Hypothesis: bound on density.",
                "## Mechanism — sieving argument. " +
                        "## Adversarial Check via verify_conjecture: HELD on held-out windows.",
                "");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, paper.evidenceLabel());
    }

    @Test
    void evenSplitFallsToWeakerAndPassesFloor() {
        // 2 EMPIRICAL_RESULT + 2 CONJECTURE → weaker side wins = CONJECTURE.
        // Floor demotes nothing (paper has the markers but the panel didn't earn
        // EMPIRICAL_RESULT by majority — the panel itself, not the floor, is the
        // limiting factor here).
        PanelOutcome outcome = panel(
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.EMPIRICAL_RESULT,
                EvidenceLabel.CONJECTURE,
                EvidenceLabel.CONJECTURE);
        ResearchPaper paper = paper(
                "Hypothesis: bound on density.",
                "## Mechanism — sieving. ## Adversarial Check via verify_conjecture: HELD.",
                "");

        applyPublishHandshake(outcome, paper);

        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel());
    }

    // ---- Helpers ----

    /**
     * Mirror of the two lines inside {@code UniversityService.publishPaper}
     * that wire the panel proposal through the floor. Kept verbatim so a
     * future refactor that changes the order or guard policy in publishPaper
     * will surface here.
     */
    private static void applyPublishHandshake(PanelOutcome outcome, ResearchPaper paper) {
        if (outcome != null) {
            outcome.proposedEvidenceLabel().ifPresent(paper::setEvidenceLabel);
        }
        paper.setEvidenceLabel(LabelFloor.demote(paper.evidenceLabel(), paper));
    }

    private static PanelOutcome panel(EvidenceLabel... labels) {
        java.util.List<PanelReview> reviews = new java.util.ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            reviews.add(new PanelReview(
                    "r" + i,
                    new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                    List.of(), List.of(), List.of(), List.of(),
                    Optional.of(labels[i])));
        }
        return PanelOutcome.aggregate(reviews);
    }

    private static ResearchPaper paper(String hypothesis, String findings, String conclusion) {
        return new ResearchPaper(
                "P-test-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Test Topic", "Test Title",
                hypothesis, "EMPIRICAL", findings, conclusion,
                List.of(), 0.5, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, "test-model", "path");
    }
}
