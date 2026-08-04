package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-22 — unit tests for the unicode-dash normalisation helper that
 * keeps {@code P‑2022‑07}-style fabricated cites from escaping the
 * paper-ID regex in {@link UniversityService}. Targets the P-a328-862
 * signature where the narrator's "(e.g., P‑2022‑07)" used U+2010 dashes.
 */
class UniversityServiceNormalizeDashTest {

    private static final Pattern PAPER_ID =
            Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    @Test
    void asciiDashStringIsPassedThroughUnchanged() {
        assertThat(UniversityService.normalizeUnicodeDashes("P-2022-07"))
                .isEqualTo("P-2022-07");
    }

    @Test
    void hyphenU2010Normalised() {
        // U+2010 HYPHEN — the narrator's most common typographic dash.
        String norm = UniversityService.normalizeUnicodeDashes("P‐2022‐07");
        assertThat(norm).isEqualTo("P-2022-07");
        assertThat(PAPER_ID.matcher(norm).find()).isTrue();
    }

    @Test
    void nonBreakingHyphenU2011Normalised() {
        String norm = UniversityService.normalizeUnicodeDashes("P‑2022‑07");
        assertThat(norm).isEqualTo("P-2022-07");
    }

    @Test
    void figureDashU2012Normalised() {
        String norm = UniversityService.normalizeUnicodeDashes("P‒2022‒07");
        assertThat(norm).isEqualTo("P-2022-07");
    }

    @Test
    void enDashU2013Normalised() {
        String norm = UniversityService.normalizeUnicodeDashes("P–2022–07");
        assertThat(norm).isEqualTo("P-2022-07");
    }

    @Test
    void emDashU2014IntentionallyNotNormalised() {
        // Em-dash is sentence-level punctuation; never appears inside a paper
        // ID by convention. Leaving it alone avoids over-normalising prose.
        String input = "see also — prior work";
        assertThat(UniversityService.normalizeUnicodeDashes(input))
                .isEqualTo(input);
    }

    @Test
    void mixedDashesInProseAllNormalised() {
        // Realistic narrator output with multiple dash variants.
        String input = "Prior work (e.g., P‐2022‐07 and P‑a328‑45).";
        String norm = UniversityService.normalizeUnicodeDashes(input);
        assertThat(norm).isEqualTo("Prior work (e.g., P-2022-07 and P-a328-45).");

        java.util.regex.Matcher m = PAPER_ID.matcher(norm);
        java.util.List<String> hits = new java.util.ArrayList<>();
        while (m.find()) hits.add(m.group());
        assertThat(hits).containsExactly("P-2022-07", "P-a328-45");
    }

    @Test
    void nullPassesThrough() {
        assertThat(UniversityService.normalizeUnicodeDashes(null)).isNull();
    }

    @Test
    void emptyStringPassesThrough() {
        assertThat(UniversityService.normalizeUnicodeDashes("")).isEqualTo("");
    }

    @Test
    void fastPathSkipsAllocationWhenNoUnicodeDashesPresent() {
        // Identity check: if no unicode dash is present, the helper should
        // return the same instance (avoids allocating a copy for the common
        // ASCII case).
        String input = "P-a328-45 with no unicode at all";
        assertThat(UniversityService.normalizeUnicodeDashes(input))
                .isSameAs(input);
    }
}
