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

    // ---- Phase 12 D1: chain-of-thought stripper ----

    @Test
    void d1_stripsClosedThinkBlock() {
        String input = "<think>\nLet me reason about this first.\n</think>\nThe peak η = 0.79 at r = 0.41.";
        String out = PromptSanitizer.stripChainOfThought(input);
        assertThat(out).doesNotContain("<think>").doesNotContain("</think>");
        assertThat(out).isEqualTo("The peak η = 0.79 at r = 0.41.");
    }

    @Test
    void d1_stripsUnclosedThinkBlockToEOF() {
        // P-a328-427 / P-a328-432 signature: opening <think> with no closing tag.
        String input = "<think>\nOkay, let's tackle this query. The user wants me to act as a scientific editor "
                + "and reformat the Findings section to be clear";
        String out = PromptSanitizer.stripChainOfThought(input);
        assertThat(out).isEmpty();
    }

    @Test
    void d1_stripsBareOkayLetsTackleOpener() {
        // Defence in depth: some vendors strip the <think> tag but keep the leak's
        // opening sentence. Strip the sentence only — preserve everything after.
        String input = "Okay, let's tackle this query. The Doppler shift was f' = 1054 Hz.";
        String out = PromptSanitizer.stripChainOfThought(input);
        assertThat(out).isEqualTo("The Doppler shift was f' = 1054 Hz.");
    }

    @Test
    void d1_leavesCleanProseUnchanged() {
        String input = "The peak η = 0.79 at r = 0.41 [call_1].";
        assertThat(PromptSanitizer.stripChainOfThought(input)).isEqualTo(input);
    }

    @Test
    void d1_nullAndBlankPassThrough() {
        assertThat(PromptSanitizer.stripChainOfThought(null)).isNull();
        assertThat(PromptSanitizer.stripChainOfThought("")).isEmpty();
        assertThat(PromptSanitizer.stripChainOfThought("   ")).isEqualTo("   ");
    }

    @Test
    void d1_sanitizeIntegratesChainOfThoughtStrip() {
        // Full sanitize pipeline: an unclosed <think> swallows everything,
        // returning empty so the upstream template fallback fires.
        String input = "<think>\nOkay, let's tackle this query. starting at position 10";
        assertThat(PromptSanitizer.sanitize(input)).isEmpty();
    }

    @Test
    void d1_detectsThinkLeak() {
        assertThat(PromptSanitizer.detects(
                "<think>\nOkay, let's tackle this query.")).isTrue();
        assertThat(PromptSanitizer.detects(
                "<think>reasoning</think>The result was X.")).isTrue();
        assertThat(PromptSanitizer.detects(
                "Okay, let's tackle this query. Result follows.")).isTrue();
        assertThat(PromptSanitizer.detects(
                "We tackle the problem of prime gaps directly.")).isFalse();
    }

    // ---- Phase 13 E5: unsubstituted "± X%" placeholder ----

    @Test
    void e5_stripsUnsubstitutedPercentPlaceholder() {
        // P-a328-467 signature: literal X in "± X%" where the renderer failed
        // to substitute the uncertainty value.
        String input  = "The maximum deviation observed was 87.65% ± X%.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("± X")
                .contains("87.65%")
                .endsWith(".");
    }

    @Test
    void e5_stripsBareUnsubstitutedX() {
        // Without the trailing percent (some templates use ± X without %).
        String input  = "Recovered ε = 0.111 ± X across five runs.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("± X")
                .contains("0.111")
                .contains("across five runs");
    }

    @Test
    void e5_preservesRealUncertaintyValue() {
        // Real numeric uncertainty must survive unchanged.
        String input  = "Recovered σ within 1.92% ± 2.3% of the reference value.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void e5_doesNotStripXInUnrelatedContext() {
        // X in unrelated context (no ± prefix) must stay.
        String input  = "The uncertainty was X.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void e5_detectsXPlaceholder() {
        assertThat(PromptSanitizer.detects("87.65% ± X%")).isTrue();
        assertThat(PromptSanitizer.detects("0.111 ± X across runs")).isTrue();
        // Real uncertainty value — not detected.
        assertThat(PromptSanitizer.detects("87.65% ± 2.3%")).isFalse();
        // X alone, no ± prefix — not detected.
        assertThat(PromptSanitizer.detects("The uncertainty was X.")).isFalse();
    }

    // ---- Phase 13 E3: Q-factor (over)/(under)/(crit) parenthetical strip ----

    @Test
    void e3_stripsQFactorRegimeParens() {
        // P-a328-458 signature.
        String input  = "Underdamped: Q=0.25(over), Critical: Q=0.50(crit), Overdamped: Q=0.13(under)";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output)
                .doesNotContain("(over)")
                .doesNotContain("(crit)")
                .doesNotContain("(under)")
                .contains("Q=0.25")
                .contains("Q=0.50")
                .contains("Q=0.13");
    }

    @Test
    void e3_qFactorParensWithSpaces() {
        String input  = "Q = 5.00 (under)";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("(under)").contains("Q = 5.00");
    }

    @Test
    void e3_preservesQFactorWithoutParens() {
        String input  = "The peak Q=5.00 was observed at low R.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void e3_detectsQFactorRegimeParen() {
        assertThat(PromptSanitizer.detects("Q=0.25(over)")).isTrue();
        assertThat(PromptSanitizer.detects("Q=0.50 (crit)")).isTrue();
        // Without the regime word — not detected.
        assertThat(PromptSanitizer.detects("Q=5.00 (peak)")).isFalse();
    }

    // -------- Phase 16 P5a — suppressBoilerplateOnNeedsRevision --------

    @Test
    void p16_p5a_gateClosedStripsBoilerplateAndAppendsDisclaimer() {
        // P-a328-553 shape: pipeline ended at gate:closed:professor /
        // publish:no_methods. The canned conclusion still got generated;
        // strip it and stamp the gate-closed disclaimer.
        String conclusion =
                "Computed values were independently reproduced by peer reviewers, "
                        + "and the topic-specific validator supports the hypothesis.";
        String callPath = "fp:openai/gpt-oss-120 → critique:partial:3_filled → "
                + "audit:4b:0gaps → gate:closed:professor → "
                + "publish:no_methods→needs_revision";

        String output = PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, callPath);
        assertThat(output).doesNotContain("independently reproduced by peer reviewers");
        assertThat(output).contains("[gate:closed");
        assertThat(output).contains("not supported by tool calls");
    }

    @Test
    void p16_p5a_needsRevisionOnlyAppendsGenericDisclaimer() {
        // No gate:closed marker — only the →needs_revision suffix. Generic
        // disclaimer fires.
        String conclusion =
                "Computed values were independently reproduced by peer reviewers. "
                        + "These findings represent an observational baseline.";
        String callPath = "fp:... → publish:single_point_sweep→needs_revision";

        String output = PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, callPath);
        assertThat(output).doesNotContain("independently reproduced by peer reviewers");
        assertThat(output).doesNotContain("observational baseline");
        assertThat(output).contains("[needs_revision");
    }

    @Test
    void p16_p5a_gateClosedPreferredOverGenericWhenBothApply() {
        // P-553 itself: callPath has BOTH gate:closed:professor and
        // →needs_revision. Gate-closed is more specific, should win.
        String conclusion =
                "Computed values were independently reproduced by peer reviewers, "
                        + "and the topic-specific validator supports the hypothesis.";
        String callPath = "fp:... → gate:closed:professor → publish:no_methods→needs_revision";

        String output = PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, callPath);
        assertThat(output).contains("[gate:closed");
        assertThat(output).doesNotContain("[needs_revision");
    }

    @Test
    void p16_p5a_cleanCallPathReturnsConclusionUnchanged() {
        // Successful peer-reviewed paper — no failure marker. Function
        // returns the conclusion unchanged.
        String conclusion =
                "Computed values were independently reproduced by peer reviewers, "
                        + "and the topic-specific validator supports the hypothesis.";
        String callPath = "fp:openai/gpt-oss-120 → audit:4b:0gaps → "
                + "prof:openai/gpt-oss-120:ok → rev:openai/gpt-oss-120:ok → "
                + "panel:n=4:agreement=0.85";

        String output = PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, callPath);
        assertThat(output).isEqualTo(conclusion);
    }

    @Test
    void p16_p5a_idempotentDoesNotDoubleAppend() {
        // Calling twice on the same conclusion should not append the
        // disclaimer twice.
        String conclusion =
                "Computed values were independently reproduced by peer reviewers, "
                        + "and the topic-specific validator supports the hypothesis.";
        String callPath = "fp:... → gate:closed:professor → publish:no_methods→needs_revision";

        String first  = PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, callPath);
        String second = PromptSanitizer.suppressBoilerplateOnNeedsRevision(first, callPath);
        // The disclaimer marker should appear exactly once.
        long count = second.chars().filter(c -> c == '[').count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void p16_p5a_nullAndBlankConclusionReturnsUnchanged() {
        String callPath = "fp:... → gate:closed:professor";
        assertThat(PromptSanitizer.suppressBoilerplateOnNeedsRevision(null, callPath)).isNull();
        assertThat(PromptSanitizer.suppressBoilerplateOnNeedsRevision("", callPath)).isEmpty();
        assertThat(PromptSanitizer.suppressBoilerplateOnNeedsRevision("   ", callPath)).isEqualTo("   ");
    }

    @Test
    void p16_p5a_nullCallPathReturnsConclusionUnchanged() {
        String conclusion = "Some conclusion text.";
        assertThat(PromptSanitizer.suppressBoilerplateOnNeedsRevision(conclusion, null))
                .isEqualTo(conclusion);
    }

    // -------- Phase 16 P5b — number-words harness-leak scrub --------

    @Test
    void p16_p5b_pA328_541_parenthesizedNumberWordsLeakStripped() {
        // P-a328-541 prose included "(starting position = fifty, scale =
        // seven point one)". Digit-form patterns missed it; words pattern
        // catches it.
        String input = "Experimental evaluation of the Reuss bound using the "
                + "declared survey parameters (starting position = fifty, scale = "
                + "seven point one) showed that the transverse stiffness approaches "
                + "the target value before the fibre volume fraction reaches unity.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("starting position = fifty");
        assertThat(output).doesNotContain("scale = seven point one");
        // The surrounding clause survives (the parens are stripped cleanly).
        assertThat(output).contains("survey parameters")
                .contains("transverse stiffness approaches");
    }

    @Test
    void p16_p5b_bareNumberWordsLeakStripped() {
        // Bare form — no parens. "starting position of fifty"
        String input  = "We ran the search, starting position of fifty, with consistent results.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("starting position of fifty");
        assertThat(output).doesNotContain("fifty");
    }

    @Test
    void p16_p5b_compoundNumberWords() {
        // "twenty-five" / "one point five" forms.
        String input  = "Survey starting position of twenty-five and scale of one point five.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("twenty-five");
        assertThat(output).doesNotContain("one point five");
    }

    @Test
    void p16_p5b_decimalNumberWords() {
        // "seven point one" decimal form.
        String input  = "The scale of seven point one was applied.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).doesNotContain("seven point one");
    }

    @Test
    void p16_p5b_legitimateNumericProseUnaffected() {
        // Words that happen to appear in prose ("one of three reviewers")
        // should not trigger; the pattern requires "starting position" /
        // "scale" / "scaling" as the lead noun.
        String input = "One of three reviewers flagged the result. The seven samples "
                + "consistently produced the same value.";
        String output = PromptSanitizer.sanitize(input);
        assertThat(output).isEqualTo(input);
    }

    @Test
    void p16_p5b_detectsNumberWordsLeak() {
        // detects() picks up both parenthesised and bare forms.
        assertThat(PromptSanitizer.detects(
                "Survey (starting position = fifty, scale = seven point one)")).isTrue();
        assertThat(PromptSanitizer.detects(
                "With starting position of twenty-five we observed...")).isTrue();
        assertThat(PromptSanitizer.detects(
                "The seven samples consistently produced...")).isFalse();
    }

    // -------- Phase 16 P6a — cross-citation verdict propagation --------

    @Test
    void p16_p6a_isRejectedStatusRejectedOnly() {
        assertThat(PromptSanitizer.isRejectedStatus("REJECTED")).isTrue();
        assertThat(PromptSanitizer.isRejectedStatus("rejected")).isTrue();
        assertThat(PromptSanitizer.isRejectedStatus("  REJECTED  ")).isTrue();
    }

    @Test
    void p16_p6a_isRejectedStatusFalseForOtherStatuses() {
        assertThat(PromptSanitizer.isRejectedStatus("PUBLISHED")).isFalse();
        assertThat(PromptSanitizer.isRejectedStatus("DISPUTED")).isFalse();
        assertThat(PromptSanitizer.isRejectedStatus("NEEDS_REVISION")).isFalse();
        assertThat(PromptSanitizer.isRejectedStatus("NULL_RESULT")).isFalse();
        // null / blank treated as "no info" → not rejected.
        assertThat(PromptSanitizer.isRejectedStatus(null)).isFalse();
        assertThat(PromptSanitizer.isRejectedStatus("")).isFalse();
        assertThat(PromptSanitizer.isRejectedStatus("  ")).isFalse();
    }

    @Test
    void p16_p6a_verdictTagRejectedPrependsDoNotPropagate() {
        // Target: P-a328-534 (REJECTED for fabricated convergence). When
        // surfacing it in priorPapers context, the tag warns the narrator
        // not to propagate its conclusions.
        String tag = PromptSanitizer.verdictTagFor("REJECTED");
        assertThat(tag).contains("REJECTED").contains("do not propagate");
        // Tag is non-empty and ends with a space so the paperId follows
        // cleanly: "[REJECTED — do not propagate] P-a328-534".
        assertThat(tag).endsWith(" ");
    }

    @Test
    void p16_p6a_verdictTagDisputedPrependsReplicationReview() {
        String tag = PromptSanitizer.verdictTagFor("DISPUTED");
        assertThat(tag).contains("DISPUTED").contains("replication review");
        assertThat(tag).endsWith(" ");
    }

    @Test
    void p16_p6a_verdictTagCleanStatusReturnsEmpty() {
        // PUBLISHED / NULL_RESULT / NEEDS_REVISION are not stigmatised at
        // citation time — the narrator can cite them cleanly.
        assertThat(PromptSanitizer.verdictTagFor("PUBLISHED")).isEmpty();
        assertThat(PromptSanitizer.verdictTagFor("NULL_RESULT")).isEmpty();
        assertThat(PromptSanitizer.verdictTagFor("NEEDS_REVISION")).isEmpty();
        assertThat(PromptSanitizer.verdictTagFor(null)).isEmpty();
        assertThat(PromptSanitizer.verdictTagFor("")).isEmpty();
        assertThat(PromptSanitizer.verdictTagFor("UNKNOWN_NEW_STATUS")).isEmpty();
    }

    @Test
    void p16_p6a_verdictTagCaseInsensitive() {
        // Status values come from various paths in UniversityService; ensure
        // the tag predicate is case-insensitive.
        assertThat(PromptSanitizer.verdictTagFor("rejected")).contains("REJECTED");
        assertThat(PromptSanitizer.verdictTagFor("Disputed")).contains("DISPUTED");
    }
}
