package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 F6 — unit tests for the Discussion-slice helper introduced for
 * the audit-4b prompt extension. The audit reviewer now sees a 150-word
 * slice of the paper's Discussion so it can flag cross-domain drift in
 * non-INTERDISCIPLINARY papers (refresh #2 §1 rec #6).
 */
class AuditPromptF6Test {

    @Test
    void truncateToWordsReturnsEmptyForNullInput() {
        assertThat(ResearchNarrationService.truncateToWords(null, 150)).isEqualTo("");
    }

    @Test
    void truncateToWordsReturnsEmptyForBlankInput() {
        assertThat(ResearchNarrationService.truncateToWords("   \n  ", 150)).isEqualTo("");
    }

    @Test
    void truncateToWordsPassesShortTextThroughVerbatim() {
        String text = "The mean stopping time agrees with the closed-form bound.";
        assertThat(ResearchNarrationService.truncateToWords(text, 150)).isEqualTo(text);
    }

    @Test
    void truncateToWordsTrimsAndCapsAtMaxWords() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (i > 0) sb.append(' ');
            sb.append("word").append(i);
        }
        String text = sb.toString();
        String out = ResearchNarrationService.truncateToWords(text, 5);
        assertThat(out).isEqualTo("word0 word1 word2 word3 word4 …");
    }
}
