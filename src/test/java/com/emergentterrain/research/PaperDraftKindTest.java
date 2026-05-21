package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 23.4 wiring — PaperDraft.kind field contract.
 *
 * <p>Pins:
 * <ul>
 *   <li>The 27-arg backwards-compat constructor defaults
 *       {@code kind} to {@link PaperKind#DOMAIN}. (Critical for the
 *       18 existing PaperDraft call sites — they don't know about
 *       PaperKind and shouldn't have to.)</li>
 *   <li>The 28-arg canonical constructor accepts an explicit kind,
 *       letting the conjecture-driven branch (23.4b) tag drafts as
 *       {@link PaperKind#CONJECTURE_INVESTIGATION}.</li>
 * </ul>
 */
class PaperDraftKindTest {

    @Test
    void backwardsCompatConstructorDefaultsToDomain() {
        PaperDraft draft = new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Some Topic",
                "Title", "raw findings", "hypothesis", "conclusion",
                "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED,
                /* plannerQuestionId = */ null);

        assertEquals(PaperKind.DOMAIN, draft.kind(),
                "27-arg constructor must default kind to DOMAIN for backwards compat");
    }

    @Test
    void canonicalConstructorAcceptsExplicitKind() {
        PaperDraft draft = new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Conjecture: abundant_density",
                "Title", "raw findings", "abundant_density <= 0.2515", "conclusion",
                "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-2", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED,
                /* plannerQuestionId = */ null,
                PaperKind.CONJECTURE_INVESTIGATION);

        assertEquals(PaperKind.CONJECTURE_INVESTIGATION, draft.kind());
    }
}
