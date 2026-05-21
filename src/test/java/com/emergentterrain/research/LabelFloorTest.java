package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 23.6 — {@link LabelFloor} contract.
 *
 * <p>Pins both directions:
 * <ul>
 *   <li>{@code maxDefensibleLabel} returns the strongest label the
 *       paper's text supports (THEOREM > EMPIRICAL_RESULT > CONJECTURE).</li>
 *   <li>{@code demote} returns the weaker of (proposed, ceiling) so a
 *       reviewer can't over-claim.</li>
 * </ul>
 */
class LabelFloorTest {

    @Test
    void bareConjecturePaperGetsConjectureCeiling() {
        ResearchPaper p = paper(
                "Hypothesis: abundant_density <= 0.2515.",
                "Mining trace: confirmed across 8 runs.",
                "Not refuted in observed regimes.");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void mechanismPlusAdversarialCheckRaisesToEmpiricalResult() {
        ResearchPaper p = paper(
                "Hypothesis: abundant_density <= 0.2515.",
                "## Mechanism\nDensity is bounded by Erdős-style sieving. " +
                        "## Adversarial Check\nverify_conjecture confirmed across 5 held-out windows.",
                "Bound holds with worst margin -0.012.");
        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void verifyConjectureToolCallAlsoQualifiesAsAdversarialCheck() {
        ResearchPaper p = paper(
                "Hypothesis: ...",
                "## Mechanism — handwave.\n" +
                        "Tool calls: verify_conjecture: abundant_density <= 0.2515 HELD across 5 windows.",
                "");
        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void mechanismAloneStaysAtConjecture() {
        ResearchPaper p = paper(
                "...",
                "## Mechanism\nThe density is bounded because... (no held-out check).",
                "...");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void proofMarkerReachesTheorem() {
        ResearchPaper p = paper(
                "Hypothesis: abundant_density <= 0.5 for n in [1, N].",
                "## Proof\nBy induction on N... Q.E.D.",
                "Established.");
        assertEquals(EvidenceLabel.THEOREM, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void demoteCapsProposedAtCeiling() {
        ResearchPaper bare = paper("h", "f", "c");
        assertEquals(EvidenceLabel.CONJECTURE,
                LabelFloor.demote(EvidenceLabel.THEOREM, bare),
                "THEOREM proposed but no proof structure → demote to CONJECTURE");
        assertEquals(EvidenceLabel.CONJECTURE,
                LabelFloor.demote(EvidenceLabel.EMPIRICAL_RESULT, bare),
                "EMPIRICAL_RESULT proposed but no mechanism+check → demote to CONJECTURE");
    }

    @Test
    void demoteAllowsWeakerOrEqualProposals() {
        ResearchPaper bare = paper("h", "f", "c");
        assertEquals(EvidenceLabel.CONJECTURE,
                LabelFloor.demote(EvidenceLabel.CONJECTURE, bare));
        assertEquals(EvidenceLabel.UNEXPLAINED_OBSERVATION,
                LabelFloor.demote(EvidenceLabel.UNEXPLAINED_OBSERVATION, bare),
                "weaker-than-ceiling labels pass through unchanged");
    }

    @Test
    void demoteAllowsEarnedLabels() {
        ResearchPaper evidenced = paper(
                "Hypothesis: bound on something.",
                "## Mechanism: bounded by sieving. ## Adversarial Check via verify_conjecture: HELD.",
                "Bound holds.");
        assertEquals(EvidenceLabel.EMPIRICAL_RESULT,
                LabelFloor.demote(EvidenceLabel.EMPIRICAL_RESULT, evidenced),
                "EMPIRICAL_RESULT proposed AND paper has mechanism+check → keep it");
    }

    @Test
    void nullProposedCoercesToConjecture() {
        ResearchPaper p = paper("h", "f", "c");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.demote(null, p));
    }

    @Test
    void nullPaperReturnsConjectureCeiling() {
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(null));
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
