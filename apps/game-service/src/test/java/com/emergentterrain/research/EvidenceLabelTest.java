package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 23.6 — pins the {@link EvidenceLabel} default + null-coalescing
 * semantics on {@link ResearchPaper}.
 *
 * <p>Why this matters for the phase doc acceptance: "spot check 20
 * papers ... none are mis-labeled THEOREM without proof structure."
 * The default-to-CONJECTURE behavior is the load-bearing piece — if a
 * paper isn't explicitly tagged with a higher label, it stays at
 * CONJECTURE. Future reviewer panels can raise the label only when
 * evidence supports it; the floor at CONJECTURE makes mis-labeling-as-
 * THEOREM impossible by default.
 */
class EvidenceLabelTest {

    @Test
    void freshPaperDefaultsToConjecture() {
        ResearchPaper paper = build();
        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel(),
                "freshly constructed ResearchPaper must default to CONJECTURE");
    }

    @Test
    void setEvidenceLabelAcceptsExplicitValues() {
        ResearchPaper paper = build();
        paper.setEvidenceLabel(EvidenceLabel.EMPIRICAL_RESULT);
        assertEquals(EvidenceLabel.EMPIRICAL_RESULT, paper.evidenceLabel());
        paper.setEvidenceLabel(EvidenceLabel.UNEXPLAINED_OBSERVATION);
        assertEquals(EvidenceLabel.UNEXPLAINED_OBSERVATION, paper.evidenceLabel());
    }

    @Test
    void setEvidenceLabelCoercesNullToConjecture() {
        ResearchPaper paper = build();
        paper.setEvidenceLabel(EvidenceLabel.EMPIRICAL_RESULT);
        paper.setEvidenceLabel(null);
        assertEquals(EvidenceLabel.CONJECTURE, paper.evidenceLabel(),
                "null label must coerce to CONJECTURE — never leaves the row in a NULL state");
    }

    @Test
    void enumOrderingReflectsClaimStrength() {
        // Ordinal order reflects strength from THEOREM (strongest) down to
        // UNEXPLAINED_OBSERVATION (weakest). Future label-floor logic will
        // rely on this ordering to demote.
        assertEquals(0, EvidenceLabel.THEOREM.ordinal());
        assertEquals(1, EvidenceLabel.EMPIRICAL_RESULT.ordinal());
        assertEquals(2, EvidenceLabel.CONJECTURE.ordinal());
        assertEquals(3, EvidenceLabel.UNEXPLAINED_OBSERVATION.ordinal());
    }

    private static ResearchPaper build() {
        return new ResearchPaper(
                "P-test-1", UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Some Topic", "Title",
                "hypothesis", "EMPIRICAL", "findings", "conclusion",
                List.of(), 0.5, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, "test-model", "path");
    }
}
