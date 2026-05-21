package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for {@link OeisAnnotator} and the underlying
 * {@link OeisCatalog}. Validates phase 23.2's acceptance criterion
 * "5 spot-checks confirm the matches are real" — we hard-pin the cited
 * A-numbers here so a typo in the catalog gets caught at test time
 * rather than at oeis.org-query time.
 */
class OeisAnnotatorTest {

    private final OeisAnnotator annotator = new OeisAnnotator(new ObjectMapper());

    @Test
    void catalogContainsExpectedReferences() {
        // Spot-check the 5 most important canonical references. Any mismatch
        // here means OeisCatalog is wrong and would mislead a paper writer.
        assertEquals("A005101", OeisCatalog.lookup("abundant_density").get(0).id());
        assertEquals("A005100", OeisCatalog.lookup("deficient_density").get(0).id());
        assertEquals("A000040", OeisCatalog.lookup("prime_density").get(0).id());
        assertEquals("A007770", OeisCatalog.lookup("happy_density").get(0).id());
        assertEquals("A002321", OeisCatalog.lookup("mertens_delta").get(0).id());
    }

    @Test
    void annotatesPureBoundConjecture() {
        // For `abundant_density <= +0.2515` (k=0), lhs is in the catalog,
        // rhs (the constant) doesn't matter — the annotator just reads lhs/rhs
        // names verbatim. Picking another known scalar for rhs to exercise
        // both sides.
        String json = annotator.annotate("abundant_density", "prime_density");
        assertNotNull(json, "annotation should be non-null when both sides have catalog entries");
        assertTrue(json.contains("A005101"), "lhs A-number should appear: " + json);
        assertTrue(json.contains("A000040"), "rhs A-number should appear: " + json);
        assertTrue(json.contains("\"has_match\":true"), "has_match flag should be true: " + json);
    }

    @Test
    void returnsNullWhenNeitherSideHasAnEntry() {
        // Both DERIVED scalars without OEIS analogs — annotator should produce
        // no JSON so the entity's oeisMatchJson stays null (sparse storage).
        String json = annotator.annotate("log_window_size", "sqrt_window_size");
        assertNull(json, "annotation should be null when neither side is in the catalog");
    }

    @Test
    void annotatesOneSidedMatch() {
        // lhs has a catalog entry, rhs doesn't. Annotation should still fire
        // with an empty rhs list and has_match=true.
        String json = annotator.annotate("abundant_density", "log_window_size");
        assertNotNull(json);
        assertTrue(json.contains("A005101"));
        assertTrue(json.contains("\"rhs\":[]"), "rhs should be empty array: " + json);
    }

    @Test
    void referencedAnumbersAreDeduped() {
        // mean_divisor_count and max_divisor_count both reference A000005.
        // The catalog reports the union deduped.
        var all = OeisCatalog.allReferencedAnumbers();
        long a000005Count = all.stream().filter(id -> id.equals("A000005")).count();
        assertEquals(1, a000005Count,
                "A000005 should appear exactly once in allReferencedAnumbers(): " + all);
    }
}
