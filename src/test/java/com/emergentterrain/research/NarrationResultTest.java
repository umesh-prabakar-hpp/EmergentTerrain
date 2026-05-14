package com.emergentterrain.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 F2 — round-trip tests for the structured-findings map shape on
 * {@link NarrationResult}. The legacy {@link NarrationResult#findings()}
 * accessor must remain bit-identical for single-entry maps (the flag-off path).
 */
class NarrationResultTest {

    @Test
    void stringShapedConstructorWrapsProseAsSingleEntryMap() {
        NarrationResult r = new NarrationResult(
                "H", "P", "C", null, "ai",
                0.5, List.of(), null);
        assertThat(r.findingsMap()).containsExactly(Map.entry(1, "P"));
        // Legacy String accessor must return the prose verbatim — bit-identical
        // to the pre-F2 String field.
        assertThat(r.findings()).isEqualTo("P");
    }

    @Test
    void nullProseStaysNullEverywhere() {
        NarrationResult r = new NarrationResult(
                null, (String) null, null, null, null,
                0.0, List.of(), null);
        assertThat(r.findingsMap()).isNull();
        assertThat(r.findings()).isNull();
    }

    @Test
    void multiEntryMapJoinsProseInKeyOrder() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(2, "second.");
        m.put(1, "first.");
        NarrationResult r = new NarrationResult(
                "H", m, "C", null, "ai",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null);
        assertThat(r.findingsMap()).containsExactlyEntriesOf(m);
        // findings() concatenates in ascending key order regardless of insertion.
        assertThat(r.findings()).isEqualTo("first.\n\nsecond.");
    }

    @Test
    void emptyMapBehavesLikeNullForLegacyConsumers() {
        NarrationResult r = new NarrationResult(
                "H", Map.<Integer, String>of(), "C", null, "ai",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null);
        assertThat(r.findings()).isNull();
    }

    @Test
    void orderedFindingsMapDropsNullAndBlankEntries() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(3, "third");
        m.put(1, "first");
        m.put(2, "  "); // blank — dropped
        m.put(4, null); // null — dropped
        Map<Integer, String> out = NarrationResult.orderedFindingsMap(m);
        assertThat(out).containsExactly(
                Map.entry(1, "first"),
                Map.entry(3, "third"));
    }

    @Test
    void withTemplateProvenancePreservesFindingsMap() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(1, "first");
        m.put(2, "second");
        NarrationResult original = new NarrationResult(
                "H", m, "C", null, "ai",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null);
        NarrationResult stamped = original.withTemplateProvenance("tpl", "v3");
        assertThat(stamped.findingsMap()).containsExactlyEntriesOf(m);
        assertThat(stamped.templateId()).isEqualTo("tpl");
        assertThat(stamped.templateVersion()).isEqualTo("v3");
    }
}
