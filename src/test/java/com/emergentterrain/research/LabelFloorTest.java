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
    void refutedAdversarialCheckCapsAtConjecture() {
        // 2026-05-29 review finding #2 — mechanism + adversarial check both present, but the
        // verify_conjecture check REFUTED the bound on a held-out window. The bound as stated is
        // false, so the strongest honest label is CONJECTURE, not EMPIRICAL_RESULT.
        ResearchPaper p = paper(
                "Hypothesis: mertens_per_sqrt_W <= 2*log_window_start - 7.76.",
                "## Mechanism\nAnalogy to the Mertens bound. ## Adversarial Check\n" +
                        "verify_conjecture: mertens_per_sqrt_W <= ... REFUTED across 5 window(s) starting at 100.",
                "Holds only for large n; refuted at small windows.");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p),
                "mechanism + adversarial check present but the check REFUTED → caps at CONJECTURE");
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
    void explicitNoMechanismIsCappedAtUnexplainedObservation() {
        ResearchPaper p = paper(
                "Hypothesis: richly_divisible_density has a finite-window ceiling.",
                "## Mechanism\nMechanism kind: none\nNo mechanism identified. "
                        + "## Adversarial Check\nverify_conjecture: HELD across 5 windows.",
                "The finite calibration is recorded without a structural explanation.");

        assertEquals(EvidenceLabel.UNEXPLAINED_OBSERVATION,
                LabelFloor.maxDefensibleLabel(p));
        assertEquals(EvidenceLabel.UNEXPLAINED_OBSERVATION,
                LabelFloor.demote(EvidenceLabel.EMPIRICAL_RESULT, p),
                "an honest no-mechanism declaration cannot be promoted by a panel");
    }

    @Test
    void proofMarkerReachesTheorem() {
        ResearchPaper p = paper(
                "Hypothesis: abundant_density <= 0.5 for n in [1, N].",
                "## Proof\nBy induction on N... Q.E.D.",
                "Established.");
        assertEquals(EvidenceLabel.THEOREM, LabelFloor.maxDefensibleLabel(p));
    }

    // ── Phase 25 §25.4 — machine-certified proof marker ──────────────

    @Test
    void proveIdentityProvedMarkerReachesTheorem() {
        // Exact shape of the MathToolSuite.proveIdentity dispatcher's
        // PROVED output: "prove_identity:" prefix + "**PROVED**" verdict.
        // Both markers present → THEOREM eligible.
        ResearchPaper p = paper(
                "Hypothesis: Σ binomial(n,k) for k from 0 to n equals 2^n.",
                "Tool calls: prove_identity: Σ binomial(n, k) for k from 0 to n. " +
                        "**PROVED** via zeilberger_wz. " +
                        "Closed form: `2**n`. Certificate: `sympy.simplify(...)`.",
                "Identity is machine-proven.");
        assertEquals(EvidenceLabel.THEOREM, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void proveIdentityProvedAlsoUnblocksDemote() {
        // Demote path: a panel that proposes THEOREM on a paper with the
        // machine-cert marker should be allowed to keep it.
        ResearchPaper p = paper(
                "Hypothesis: Σ k from 0 to n = n(n+1)/2.",
                "prove_identity: ... **PROVED** via gosper. " +
                        "Certificate: `k*(k - 1)/2`.",
                "Proven.");
        assertEquals(EvidenceLabel.THEOREM,
                LabelFloor.demote(EvidenceLabel.THEOREM, p));
    }

    @Test
    void bareProvedWordWithoutToolNameStaysAtConjecture() {
        // Prose that says "I proved this" but doesn't carry the tool
        // trace must NOT earn THEOREM. The marker pair is conservative
        // by design — single-marker prose triggers no upgrade.
        ResearchPaper p = paper(
                "Hypothesis: Σ k from 0 to n = n(n+1)/2.",
                "**PROVED** by hand using induction.",  // marker without prove_identity
                "Done.");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p));
    }

    @Test
    void prove_identityMentionWithoutProvedVerdictStaysBelow() {
        // The tool was called but returned no_closed_form (or wasn't
        // PROVED). The dispatcher's output for no_closed_form contains
        // "prove_identity" but NOT "**PROVED**" — floor must keep
        // CONJECTURE.
        ResearchPaper p = paper(
                "Hypothesis: Σ 1/k^2 for k from 1 to n.",
                "prove_identity returned **no_closed_form**. " +
                        "Sympy could not reduce this sum.",
                "Claim remains a conjecture.");
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p));
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

    // ── Phase 26 §26.5 — KNOWN_RESULT carve-out ──────────────────────

    @Test
    void demote_knownResultProposed_passesThroughRegardlessOfPaperText() {
        // Even a paper with full proof structure can't UPGRADE
        // KNOWN_RESULT to THEOREM. A rediscovery stays a rediscovery.
        ResearchPaper proofPaper = paper(
                "Hypothesis: π²/6.",
                "## Proof\nBy direct evaluation... Q.E.D.",
                "Established.");
        assertEquals(EvidenceLabel.KNOWN_RESULT,
                LabelFloor.demote(EvidenceLabel.KNOWN_RESULT, proofPaper));
    }

    @Test
    void demote_knownResultProposed_passesThroughOnBarePaper() {
        // Bare paper text with KNOWN_RESULT proposed → still KNOWN_RESULT.
        // The floor doesn't second-guess the recognition pipeline.
        ResearchPaper bare = paper("h", "f", "c");
        assertEquals(EvidenceLabel.KNOWN_RESULT,
                LabelFloor.demote(EvidenceLabel.KNOWN_RESULT, bare));
    }

    @Test
    void maxDefensibleLabel_neverReturnsKnownResult() {
        // The floor's structural-text check has no "this is a
        // rediscovery" marker — KNOWN_RESULT can only be set by the
        // explicit recognition path, never by maxDefensibleLabel.
        ResearchPaper p = paper(
                "Hypothesis: π²/6.",
                "Findings: identified as π²/6 [cite-recognised:pslq:pi^2].",
                "Known constant.");
        // Even with a recognition citation in prose, the floor returns
        // CONJECTURE (its default) — not KNOWN_RESULT. KNOWN_RESULT is
        // a propose-and-respect signal, not a structural floor signal.
        assertEquals(EvidenceLabel.CONJECTURE, LabelFloor.maxDefensibleLabel(p));
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
