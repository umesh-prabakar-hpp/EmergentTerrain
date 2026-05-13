package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 A4 unit tests for {@link PromptSanitizer}. */
class PromptSanitizerTest {

    // ---- null / blank tolerance ----

    @Test
    void nullInputReturnsNull() {
        assertThat(PromptSanitizer.sanitize(null)).isNull();
    }

    @Test
    void blankInputReturnedUnchanged() {
        assertThat(PromptSanitizer.sanitize("")).isEmpty();
        assertThat(PromptSanitizer.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    void cleanTextReturnedUnchanged() {
        String input = "Aliquot sequences in [10, 50) will predominantly terminate.";
        assertThat(PromptSanitizer.sanitize(input)).isEqualTo(input);
    }

    // ---- "starting at position N" ----

    @Test
    void stripsStartingAtPositionWithCommaWrapper() {
        String input  = "Aliquot sequences in [10, 50), starting at position 10, will terminate.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("starting at position")
                .doesNotContain(", ,")
                .startsWith("Aliquot sequences in [10, 50)")
                .endsWith("will terminate.");
    }

    @Test
    void stripsStartingAtPositionWithAndConnector() {
        String input  = "The search will run and starting at position 7 cover the full range.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("starting at position")
                .doesNotContain(" and ")
                .contains("The search will run");
    }

    // ---- "uses a scale of N" ----

    @Test
    void stripsUsesAScaleOf() {
        String input  = "The search begins at n=10 and uses a scale of 7.0 to set the radius.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("scale of 7.0")
                .contains("The search begins at n=10")
                .contains("to set the radius");
    }

    @Test
    void stripsUsesScaleAlternatePhrasing() {
        // 'use a scale' (no 's') as the LLM sometimes drops the conjugation.
        String input  = "Pipeline use a scale of 1.5 to clamp the prior.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("scale of");
    }

    // ---- "quality score of N" sentence ----

    @Test
    void stripsQualityScoreSentenceLeak() {
        String input  = "Results show a strong effect. The actual quality score of 0.23 remained unaffected by changes in sampling parameters.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("quality score")
                .contains("Results show a strong effect.");
    }

    @Test
    void stripsBareQualityScoreSentence() {
        // Variant without "the actual" prefix.
        String input  = "Measurements completed. Quality score of 0.45 was observed.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("Quality score")
                .contains("Measurements completed");
    }

    // ---- detects() telemetry ----

    @Test
    void detectsReturnsTrueWhenAnyLeakPresent() {
        assertThat(PromptSanitizer.detects("X starting at position 5.")).isTrue();
        assertThat(PromptSanitizer.detects("Y uses a scale of 2.0.")).isTrue();
        assertThat(PromptSanitizer.detects("Quality score of 0.5 was recorded.")).isTrue();
    }

    @Test
    void detectsReturnsFalseForCleanText() {
        assertThat(PromptSanitizer.detects("Aliquot sequences will terminate.")).isFalse();
        assertThat(PromptSanitizer.detects(null)).isFalse();
        assertThat(PromptSanitizer.detects("")).isFalse();
    }
}
