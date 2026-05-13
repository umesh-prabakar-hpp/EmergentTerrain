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

    // ---- Phase 7 G4 — additional patterns ----

    @Test
    void phase7_G4_stripsObservedFromPositionAndScale() {
        // The P-237 leak from REVIEW_REPORT.md refresh #3 (rec #14).
        String input  = "Critical CTE remains below yield, as observed from the starting "
                + "position of 10 and scale of 7.7.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("starting position")
                .doesNotContain("scale of 7.7")
                .startsWith("Critical CTE remains below yield");
    }

    @Test
    void phase7_G4_stripsWithAScaleOfLeak() {
        // The P-256 / P-242 leak (rec #14).
        String input  = "Aliquot sequences in [528, 568) with a scale of 1.0 will terminate.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("scale of 1.0")
                .contains("Aliquot sequences in [528, 568)")
                .contains("will terminate");
    }

    @Test
    void phase7_G4_stripsStartingFromParametricIndex() {
        // The P-250 / P-288 leak (rec #14).
        String input  = "Starting from parametric index 56, the Reuss-bound stiffness rises.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("parametric index")
                .contains("the Reuss-bound stiffness rises");
    }

    @Test
    void phase7_G4_stripsDummyCitationPlaceholder() {
        // The P-300 leak (rec #33) — narrator emitted the prompt template's literal
        // placeholder instead of a real citation.
        String input  = "Peak packing efficiency of 79.2% at r≈0.412 (see P-xxxx-1).";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("P-xxxx-1")
                .doesNotContain("(see ")
                .contains("Peak packing efficiency of 79.2% at r≈0.412");
    }

    @Test
    void phase7_G4_stripsDummyCitationVariableXLength() {
        // Variant lengths of x's in the placeholder.
        assertThat(PromptSanitizer.sanitize("text (see P-xx-1).")).doesNotContain("P-xx-1");
        assertThat(PromptSanitizer.sanitize("text (see P-xxxx-3).")).doesNotContain("P-xxxx-3");
    }

    @Test
    void phase7_G4_fixesScientificNotationFormattingBug() {
        // The P-274 / P-287 leak (rec #26). "3.89×10^1" must collapse to "3.89".
        String input  = "Maximum multiplier of 3.89×10^1 observed for high Ea range.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("10^1")
                .doesNotContain("×10")
                .contains("Maximum multiplier of 3.89")
                .contains("observed for high Ea range");
    }

    @Test
    void phase7_G4_scientificNotationFix_handlesLowercaseX() {
        String input  = "Result: 1.14x10^1 observed.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).contains("1.14").doesNotContain("10^1");
    }

    @Test
    void phase7_G4_scientificNotationFix_doesNotTouchValid10ToTheN() {
        // 10^2, 10^3, etc. should NOT be stripped — only the buggy 10^1 case.
        String input  = "The reaction rate scales as 10^3 at higher temperatures.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void phase7_G4_detectsAllNewPatterns() {
        assertThat(PromptSanitizer.detects("the starting position of 10 and scale of 7.7")).isTrue();
        assertThat(PromptSanitizer.detects("evaluated with a scale of 1.0 here")).isTrue();
        assertThat(PromptSanitizer.detects("starting from parametric index 56")).isTrue();
        assertThat(PromptSanitizer.detects("(see P-xxxx-1)")).isTrue();
        assertThat(PromptSanitizer.detects("3.89×10^1")).isTrue();
    }
}
