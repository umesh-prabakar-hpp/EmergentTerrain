package com.emergentterrain.service;

import com.emergentterrain.research.FrontierDomain;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PaperKind;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path B (2026-06-05) — professor frontierHint contract. The seed findings'
 * discovery steering only ever reaches the tool-less first-pass narrator
 * (trace-verified on P-b72c-128: 6/6 frontier-framed papers, zero discover_*
 * calls); this hint is the tool-bearing stage's replacement. Mockito-free per
 * the dev-shell JDK25 constraint.
 */
class FrontierHintTest {

    private static PaperDraft draft(FrontierDomain fd) {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "conjecture/max_divisor_count",
                "Title", "raw findings", "hypothesis", "conclusion",
                "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-fh", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED,
                null, PaperKind.CONJECTURE_INVESTIGATION, fd);
    }

    @Test
    void nullForNonFrontierDraft() {
        assertNull(ResearchNarrationService.buildFrontierHint(draft(null)));
        assertNull(ResearchNarrationService.buildFrontierHint(null));
    }

    @Test
    void divisorHintNamesToolBoundKeyAndComputedFraming() {
        String hint = ResearchNarrationService.buildFrontierHint(draft(FrontierDomain.DIVISOR));
        assertTrue(hint.contains("discover_divisor_value"), "must name the compute tool");
        assertTrue(hint.contains("recall_frontier_knowledge"), "must name the recall tool");
        assertTrue(hint.contains("divisor_D"), "must carry the bound key for recall");
        assertTrue(hint.contains("COMPUTED"), "must keep the honest computed-not-established framing");
        assertTrue(hint.contains("2,000,000"), "must state the sieve ceiling");
    }

    @Test
    void mertensHintUsesItsOwnToolAndKey() {
        String hint = ResearchNarrationService.buildFrontierHint(draft(FrontierDomain.MERTENS));
        assertTrue(hint.contains("discover_mertens_value"));
        assertTrue(hint.contains("mertens_M"));
    }

    @Test
    void joinHintsSkipsNullAndBlank() {
        assertNull(ResearchNarrationService.joinHints(null, null, "  "));
        assertEquals("a", ResearchNarrationService.joinHints(null, "a"));
        assertEquals("a\nb", ResearchNarrationService.joinHints("a", null, "b"));
    }
}
