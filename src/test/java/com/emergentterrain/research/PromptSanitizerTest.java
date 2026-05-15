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

    // ---- Phase 9 G7 — variants from research/a3289005 refresh ----

    @Test
    void phase9_G7_stripsWhenSearchStartsAtPosition() {
        // P-a328-326 verbatim — sub-clause connected by "when" that the
        // CONNECTOR_PREFIX-anchored patterns can't reach.
        String input  = "the Reuss-bound effective transverse modulus will not reach 2 Em"
                + " until the fibre volume fraction is well above 0.5, approaching unity"
                + " when the search starts at position 31";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("search starts")
                .doesNotContain("position 31")
                .endsWith("approaching unity");
    }

    @Test
    void phase9_G7_stripsStartsNearPositionAndScalingFactor() {
        // P-a328-341 verbatim — "(that) start(s) near position N" + "with a
        // scaling factor of N", linked by "and".
        String input  = "For intervals of width 200 that start near position 270 and"
                + " are examined with a scaling factor of 5.5, the empirical proportion"
                + " of square-free numbers stays within 5% of 6/π²≈0.6079.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("position 270")
                .doesNotContain("scaling factor")
                .contains("For intervals of width 200")
                .contains("empirical proportion");
    }

    @Test
    void phase9_G7_stripsEmdashHarnessClause() {
        // P-a328-329 verbatim — em-dash-delimited parenthetical containing
        // "starting cycle index of N" + "with a scale factor of N".
        String input  = "the power-law relation σ_a = σ'_f·(2·N_f)^b—applied from a"
                + " starting cycle index of 41 with a scale factor of 6.2—will generate"
                + " stress-amplitude values below the UTS.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("cycle index")
                .doesNotContain("scale factor")
                .doesNotContain("—applied")
                .contains("σ_a = σ'_f·(2·N_f)^b")
                .contains("will generate");
    }

    @Test
    void phase9_G7_stripsStartingCycleIndexOfWithoutEmdash() {
        // Variant without em-dashes — granular pattern must still fire.
        String input  = "The sweep, starting cycle index of 100, covers the full range.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("cycle index")
                .contains("The sweep")
                .contains("covers the full range");
    }

    @Test
    void phase9_G7_extendedUsesAScalingFactor() {
        // The existing USES_A_SCALE_OF should now also catch "uses a scaling
        // factor of N".
        String input  = "The model uses a scaling factor of 2.5 to normalize.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("scaling factor")
                .contains("The model")
                .contains("to normalize");
    }

    @Test
    void phase9_G7_detectsAllNewPatterns() {
        assertThat(PromptSanitizer.detects("when the search starts at position 31")).isTrue();
        assertThat(PromptSanitizer.detects("that start near position 270")).isTrue();
        assertThat(PromptSanitizer.detects("starting cycle index of 41")).isTrue();
        assertThat(PromptSanitizer.detects("with a scaling factor of 5.5")).isTrue();
        assertThat(PromptSanitizer.detects("X—starting cycle index of 1 Y—Z")).isTrue();
    }

    @Test
    void phase9_G7_doesNotTouchLegitimatePhysicsScaleFactor() {
        // Phrases like "the cosmological scale factor a(t)" or "a Lorentz factor
        // of 1.5" should NOT be stripped. SCALING_FACTOR_OF only matches inside
        // a "(uses|with) a …" preamble, not bare "scale factor of N".
        String input = "The cosmological scale factor a(t) grows with time.";
        assertThat(PromptSanitizer.sanitize(input)).isEqualTo(input);
    }

    // ---- Phase 7 G5 — boilerplate suppression on critique:skip ----

    @Test
    void phase7_G5_suppressesBoilerplateWhenCritiqueSkip() {
        // Verbatim 3-sentence boilerplate observed in 17/19 corpus papers at refresh #1.
        String input = "Computed values were independently reproduced by peer reviewers. "
                + "These findings represent an observational baseline. "
                + "The computed values are finite and reproduced under the search params declared above.";
        String callPath = "fp:llama-3.3-70b-vers → critique:skip:incomplete_firstpass";

        String output = PromptSanitizer.suppressBoilerplateOnSkip(input, callPath);

        assertThat(output)
                .doesNotContain("independently reproduced by peer reviewers")
                .doesNotContain("observational baseline")
                .doesNotContain("finite and reproduced under the search params")
                .contains("[critique:skip");
    }

    @Test
    void phase7_G5_leavesConclusionUnchangedWhenPeerReviewRan() {
        String input = "Computed values were independently reproduced by peer reviewers. "
                + "These findings represent an observational baseline.";
        String callPath = "fp:llama-3.3-70b-vers → audit:4b:0gaps → rev:openai/gpt-oss-120b:ok";

        // critique:skip absent — leave the conclusion alone (peer review actually ran).
        assertThat(PromptSanitizer.suppressBoilerplateOnSkip(input, callPath)).isEqualTo(input);
    }

    @Test
    void phase7_G5_preservesNonBoilerplateContent() {
        String input = "The pre-registered hypothesis was confirmed. "
                + "Computed values were independently reproduced by peer reviewers. "
                + "An open question is how the bound shifts with temperature.";
        String callPath = "fp:llama-3.3-70b-vers → critique:skip:incomplete_firstpass";

        String output = PromptSanitizer.suppressBoilerplateOnSkip(input, callPath);

        // Boilerplate sentence removed; surrounding sentences preserved verbatim.
        assertThat(output)
                .doesNotContain("independently reproduced")
                .contains("The pre-registered hypothesis was confirmed.")
                .contains("An open question is how the bound shifts with temperature.")
                .contains("[critique:skip");
    }

    @Test
    void phase7_G5_handlesNullAndBlankInputs() {
        assertThat(PromptSanitizer.suppressBoilerplateOnSkip(null, "critique:skip")).isNull();
        assertThat(PromptSanitizer.suppressBoilerplateOnSkip("", "critique:skip")).isEmpty();
        assertThat(PromptSanitizer.suppressBoilerplateOnSkip("text", null)).isEqualTo("text");
    }

    @Test
    void phase7_G5_doesNotDoubleStampDisclaimer() {
        String input = "Computed values were independently reproduced by peer reviewers.";
        String callPath = "critique:skip:incomplete_firstpass";

        // Run twice — disclaimer should appear exactly once.
        String once  = PromptSanitizer.suppressBoilerplateOnSkip(input, callPath);
        String twice = PromptSanitizer.suppressBoilerplateOnSkip(once, callPath);

        long count = twice.split("\\[critique:skip", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }

    // ── Phase 10 — paramN= literal leak patterns ────────────────────────────

    @Test
    void phase10_stripsParenthesisedParamPair_P344Hypothesis() {
        // P-a328-344 Hypothesis verbatim shape — the most common leak form.
        String input = "Using the primary parameters (param1=53, param2=5.3) the "
                + "computed amplitudes decrease from 287 MPa to 210 MPa.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("param1").doesNotContain("param2");
        assertThat(out).contains("primary parameters")
                .contains("computed amplitudes decrease");
    }

    @Test
    void phase10_stripsParenthesisedSingleParam_P344Findings() {
        // P-a328-344 Findings has two single-param parentheticals next to each
        // other; both must be removed cleanly.
        String input = "An additional sweep with a lower starting point (param1=10) "
                + "and larger scale factor (param2=1.5) also follows the Basquin law.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("param1=").doesNotContain("param2=");
        assertThat(out).contains("Basquin law").contains("lower starting point");
    }

    @Test
    void phase10_stripsParenthesisedLabel_P347() {
        // P-a328-347 Hypothesis verbatim — the (param2)/(param1) tag-label form.
        String input = "using a scaling parameter of 6.9 (param2) and a "
                + "truncation depth of 22 terms (param1)";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("(param1)").doesNotContain("(param2)");
        assertThat(out).contains("scaling parameter of 6.9")
                .contains("truncation depth of 22 terms");
    }

    @Test
    void phase10_stripsBareParamAssign_P355() {
        // P-a328-355 Findings: "Experimental sweeps with param1=49 and param2=2.1"
        String input = "Experimental sweeps with param1=49 and param2=2.1 show the ratio.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("param1").doesNotContain("param2");
        assertThat(out).contains("Experimental sweeps").contains("show the ratio");
    }

    @Test
    void phase10_stripsBareParamAssignWithUnit_P362() {
        // P-a328-362 Hypothesis: "initial displacement param1=46° and scale param2=4.4 m"
        String input = "For a pendulum with initial displacement param1=46° and "
                + "scale param2=4.4 m, the small-angle approximation will produce error.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("param1").doesNotContain("param2");
        // Subject remains coherent after the leak strip.
        assertThat(out).contains("pendulum").contains("small-angle approximation");
    }

    @Test
    void phase10_detectsAllParamForms() {
        assertThat(PromptSanitizer.detects("(param1=10, param2=1.5)")).isTrue();
        assertThat(PromptSanitizer.detects("with (param2=6.9) here")).isTrue();
        assertThat(PromptSanitizer.detects("annotated (param1) inline")).isTrue();
        assertThat(PromptSanitizer.detects("param1=49 in sweep")).isTrue();
        // Clean prose — no false positive.
        assertThat(PromptSanitizer.detects("the parameters were chosen carefully")).isFalse();
    }

    // ── Phase 10 follow-on — bare-phrasing leak forms ───────────────────────

    @Test
    void phase10_followOn_stripsBarePositionAndScale_P375() {
        // P-a328-375 Findings verbatim — the bare form that slipped past
        // OBSERVED_FROM_POSITION_AND_SCALE because it has no "the" / "of".
        String input = "Fitting the allometric relationship across 54 body masses "
                + "centered at M=8.00 with starting position 15 and scale 8.5 "
                + "yielded an exponential coefficient of 0.750.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("starting position 15");
        assertThat(out).doesNotContain("scale 8.5");
        // The surrounding sentence stays coherent.
        assertThat(out).contains("Fitting the allometric relationship")
                .contains("exponential coefficient of 0.750");
    }

    @Test
    void phase10_followOn_stripsBareIndexAndScaleFactor_P376() {
        // P-a328-376 Hypothesis verbatim — the bare-index form with no "of"
        // connector. STARTING_INDEX_OF required "of"; STARTING_INDEX_BARE does not.
        String input = "Within the explored grain-size window (starting index 53, "
                + "scale factor 5.3), the yield strength follows σ_y = σ_0 + k·d^(-1/2).";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("starting index 53");
        assertThat(out).doesNotContain("scale factor 5.3");
        assertThat(out).contains("grain-size window")
                .contains("yield strength follows σ_y");
    }

    @Test
    void phase10_followOn_stripsBareIndexAlone() {
        // The "scale factor" tail is optional — bare "starting index N" must
        // also strip cleanly when the LLM omits the second value.
        String input = "An additional sweep starting index 22 gave consistent results.";
        String out = PromptSanitizer.sanitize(input);
        assertThat(out).doesNotContain("starting index 22");
        assertThat(out).contains("additional sweep").contains("consistent results");
    }

    @Test
    void phase10_followOn_detectsBareForms() {
        assertThat(PromptSanitizer.detects(
                "with starting position 15 and scale 8.5 yielded results")).isTrue();
        assertThat(PromptSanitizer.detects(
                "(starting index 53, scale factor 5.3)")).isTrue();
        assertThat(PromptSanitizer.detects(
                "starting index 7 follows the relation")).isTrue();
        // Pre-existing "of" forms still detected (regression guard).
        assertThat(PromptSanitizer.detects(
                "the starting position of 10 and scale of 7.7")).isTrue();
        // Clean prose — no false positive even though it contains "starting".
        assertThat(PromptSanitizer.detects(
                "we are starting our analysis at the lowest grain size")).isFalse();
    }
}
