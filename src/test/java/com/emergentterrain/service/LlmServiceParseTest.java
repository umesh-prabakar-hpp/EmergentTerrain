package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the static JSON parse methods on LlmService.
 * These run without Spring, without an LLM, and without I/O — pure JSON parsing only.
 *
 * parseFormulationResult — LLM response before computation: param1, param2, pre-registration
 * parseNarrationResult   — LLM response after computation: hypothesis, findings, conclusion,
 *                          confidenceScore, cited_paper_ids, followUp
 * parseRefinementDecision — LLM yes/no on whether to run another computation step
 */
class LlmServiceParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==========================================================================
    // parseFormulationResult
    // ==========================================================================

    @Test
    void formulationParsesValidJson() {
        String json = """
                {"param1":42,"param2":3.5,"question":"Does Collatz stopping time peak near 42?",
                "pre_registration":{"hypothesis":"Stopping times peak near 42",
                "prediction":"max_steps > 150","falsification_condition":"max_steps < 50",
                "mapping_notes":""}}
                """;
        var result = FormulationService.parseFormulationResult(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(42);
        assertThat(result.param2()).isEqualTo(3.5);
        assertThat(result.researchQuestion()).contains("42");
        assertThat(result.preRegistration()).isNotNull();
        assertThat(result.preRegistration().hypothesis()).contains("peak near 42");
        assertThat(result.preRegistration().prediction()).isEqualTo("max_steps > 150");
        assertThat(result.preRegistration().falsificationCondition()).isEqualTo("max_steps < 50");
    }

    @Test
    void formulationRejectsParam1Below10() {
        String json = """
                {"param1":5,"param2":3.0,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(FormulationService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam1Above99() {
        String json = """
                {"param1":100,"param2":3.0,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(FormulationService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam2Below1() {
        String json = """
                {"param1":50,"param2":0.5,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(FormulationService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam2Above9() {
        String json = """
                {"param1":50,"param2":9.5,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(FormulationService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationReturnsNullForBlankInput() {
        assertThat(FormulationService.parseFormulationResult(MAPPER, null)).isNull();
        assertThat(FormulationService.parseFormulationResult(MAPPER, "")).isNull();
        assertThat(FormulationService.parseFormulationResult(MAPPER, "  ")).isNull();
    }

    @Test
    void formulationReturnsNullForNonJson() {
        assertThat(FormulationService.parseFormulationResult(MAPPER, "Sorry, I cannot help with that.")).isNull();
    }

    @Test
    void formulationWithPreambleAndJsonExtractedCorrectly() {
        String response = "Sure, here is my choice:\n"
                + "{\"param1\":75,\"param2\":2.0,\"question\":\"Explore upper range\","
                + "\"pre_registration\":{\"hypothesis\":\"High param1 yields peaks\","
                + "\"prediction\":\"value > 200\",\"falsification_condition\":\"value < 100\","
                + "\"mapping_notes\":\"\"}}";
        var result = FormulationService.parseFormulationResult(MAPPER, response);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(75);
    }

    @Test
    void formulationWithMissingPreRegistrationStillReturnsValidParams() {
        // LLM omits the pre_registration field — params are still valid
        String json = "{\"param1\":30,\"param2\":4.0,\"question\":\"No pre-reg\"}";
        var result = FormulationService.parseFormulationResult(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(30);
        assertThat(result.preRegistration()).isNull();
    }

    @Test
    void formulationPreRegistrationRequiresBothHypothesisAndPrediction() {
        // hypothesis present but prediction is blank → preRegistration stays null
        String json = "{\"param1\":50,\"param2\":2.0,\"question\":\"q\","
                + "\"pre_registration\":{\"hypothesis\":\"h\",\"prediction\":\"\","
                + "\"falsification_condition\":\"f\",\"mapping_notes\":\"\"}}";
        var result = FormulationService.parseFormulationResult(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.preRegistration()).isNull();
    }

    @Test
    void formulationBoundaryParam1At10And99AreAccepted() {
        String json10 = "{\"param1\":10,\"param2\":1.0,\"question\":\"q\","
                + "\"pre_registration\":{\"hypothesis\":\"h\",\"prediction\":\"p\","
                + "\"falsification_condition\":\"f\",\"mapping_notes\":\"\"}}";
        String json99 = "{\"param1\":99,\"param2\":9.0,\"question\":\"q\","
                + "\"pre_registration\":{\"hypothesis\":\"h\",\"prediction\":\"p\","
                + "\"falsification_condition\":\"f\",\"mapping_notes\":\"\"}}";
        assertThat(FormulationService.parseFormulationResult(MAPPER, json10)).isNotNull();
        assertThat(FormulationService.parseFormulationResult(MAPPER, json99)).isNotNull();
    }

    // ==========================================================================
    // parseNarrationResult
    // ==========================================================================

    @Test
    void narrationParsesFullValidResponse() {
        String json = """
                {"hypothesis":"Collatz chains peak near 42",
                "findings":"The computation found max_steps=178 at n=871",
                "conclusion":"The pre-registered prediction was confirmed",
                "confidenceScore":0.85,
                "cited_paper_ids":["P-a29f-10","P-a29f-22"],
                "followUp":{"param1":45,"param2":3.0,"predictedScore":0.72}}
                """;
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(result.hypothesis()).contains("Collatz");
        assertThat(result.findings()).contains("max_steps");
        assertThat(result.conclusion()).contains("confirmed");
        assertThat(result.confidenceScore()).isEqualTo(0.85);
        assertThat(result.citedPaperIds()).containsExactly("P-a29f-10", "P-a29f-22");
        assertThat(result.followUp()).isNotNull();
        assertThat(result.followUp().param1()).isEqualTo(45);
        assertThat(result.followUp().param2()).isEqualTo(3.0);
        assertThat(result.followUp().predictedScore()).isEqualTo(0.72);
    }

    @Test
    void narrationDefaultsConfidenceTo0_5WhenAbsent() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"cited_paper_ids\":[]}";
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(result.confidenceScore()).isEqualTo(0.5);
    }

    @Test
    void narrationClampsConfidenceScoreToRange() {
        String tooHigh = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":1.5,\"cited_paper_ids\":[]}";
        String tooLow  = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":-0.3,\"cited_paper_ids\":[]}";
        assertThat(ResearchNarrationService.parseNarrationResult(MAPPER, tooHigh).confidenceScore()).isEqualTo(1.0);
        assertThat(ResearchNarrationService.parseNarrationResult(MAPPER, tooLow).confidenceScore()).isEqualTo(0.0);
    }

    @Test
    void narrationFiltersMalformedPaperIds() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,"
                + "\"cited_paper_ids\":[\"P-a29f-10\",\"not-a-paper-id\",\"P-zzzz-5\","
                + "\"P-1234-99\"]}";
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        // P-a29f-10 and P-1234-99 are valid; "not-a-paper-id" and "P-zzzz-5" are not
        assertThat(result.citedPaperIds())
                .contains("P-a29f-10", "P-1234-99")
                .doesNotContain("not-a-paper-id", "P-zzzz-5");
    }

    @Test
    void narrationDiscardsFollowUpWithInvalidParams() {
        // param1=5 is out of range (must be 10–99)
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[],"
                + "\"followUp\":{\"param1\":5,\"param2\":3.0,\"predictedScore\":0.4}}";
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(result.followUp()).isNull();
    }

    @Test
    void narrationDiscardsFollowUpPredictedScoreOutOfRange() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[],"
                + "\"followUp\":{\"param1\":50,\"param2\":3.0,\"predictedScore\":1.5}}";
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        // followUp itself is valid (params in range), but predictedScore is discarded
        assertThat(result.followUp()).isNotNull();
        assertThat(result.followUp().predictedScore()).isNull();
    }

    @Test
    void narrationReturnsEmptyDefaultForNullInput() {
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, null);
        assertThat(result.hypothesis()).isNull();
        assertThat(result.findings()).isNull();
        assertThat(result.conclusion()).isNull();
        assertThat(result.followUp()).isNull();
        assertThat(result.confidenceScore()).isEqualTo(0.5);
        assertThat(result.citedPaperIds()).isEmpty();
    }

    @Test
    void narrationReturnsEmptyDefaultForBlankInput() {
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, "   ");
        assertThat(result.confidenceScore()).isEqualTo(0.5);
        assertThat(result.citedPaperIds()).isEmpty();
    }

    @Test
    void narrationReturnsEmptyDefaultForMalformedJson() {
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, "not json at all");
        assertThat(result.hypothesis()).isNull();
        assertThat(result.confidenceScore()).isEqualTo(0.5);
    }

    @Test
    void narrationExtractsJsonFromResponseWithPreamble() {
        String response = "Here is the narration:\n"
                + "{\"hypothesis\":\"Prime gaps widen near 50\","
                + "\"findings\":\"Gap distribution is non-uniform\","
                + "\"conclusion\":\"The hypothesis is partially confirmed\","
                + "\"confidenceScore\":0.6,\"cited_paper_ids\":[]}";
        NarrationResult result = ResearchNarrationService.parseNarrationResult(MAPPER, response);
        assertThat(result.hypothesis()).contains("Prime gaps");
        assertThat(result.confidenceScore()).isEqualTo(0.6);
    }

    @Test
    void narrationEmptyCitedPaperIdsArrayProducesEmptyList() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.7,\"cited_paper_ids\":[]}";
        assertThat(ResearchNarrationService.parseNarrationResult(MAPPER, json).citedPaperIds()).isEmpty();
    }

    @Test
    void narrationAbsentFollowUpFieldProducesNullFollowUp() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        assertThat(ResearchNarrationService.parseNarrationResult(MAPPER, json).followUp()).isNull();
    }

    // ==========================================================================
    // Phase 7 F2 — parseNarrationResult learns the Map<Integer, String> findings shape
    // ==========================================================================

    @Test
    void flatStringFindingsWrappedAsSingleEntryMap() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"flat prose\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap()).containsExactly(java.util.Map.entry(1, "flat prose"));
        // Legacy String accessor returns the prose verbatim — flag-off shape.
        assertThat(r.findings()).isEqualTo("flat prose");
    }

    @Test
    void mapShapedFindingsParsesEveryComputationBlock() {
        String json = """
                {"hypothesis":"h",
                 "findings":{"computation_1":"first block.","computation_2":"second block."},
                 "conclusion":"c","confidenceScore":0.7,"cited_paper_ids":[]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap()).containsExactly(
                java.util.Map.entry(1, "first block."),
                java.util.Map.entry(2, "second block."));
    }

    @Test
    void mapShapedFindingsTolerateBareIntegerKeys() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{\"1\":\"first\",\"2\":\"second\",\"3\":\"third\"},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap()).containsExactly(
                java.util.Map.entry(1, "first"),
                java.util.Map.entry(2, "second"),
                java.util.Map.entry(3, "third"));
    }

    @Test
    void mapShapedFindingsSortsKeysAscending() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{\"computation_3\":\"third\",\"computation_1\":\"first\",\"computation_2\":\"second\"},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap().keySet()).containsExactly(1, 2, 3);
    }

    @Test
    void mapShapedFindingsDropsBlankAndNullBlocks() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{\"computation_1\":\"first\",\"computation_2\":\"   \",\"computation_3\":null,\"computation_4\":\"fourth\"},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap().keySet()).containsExactly(1, 4);
    }

    @Test
    void mapShapedFindingsSkipsKeysThatAreNotPositiveIntegers() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{\"computation_0\":\"zero\",\"computation_-1\":\"neg\",\"computation_abc\":\"junk\",\"computation_2\":\"second\"},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap()).containsExactly(java.util.Map.entry(2, "second"));
    }

    @Test
    void emptyFindingsObjectProducesNullMap() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findingsMap()).isNull();
        assertThat(r.findings()).isNull();
    }

    @Test
    void multiBlockFindingsLegacyAccessorJoinsByBlankLine() {
        String json = "{\"hypothesis\":\"h\","
                + "\"findings\":{\"computation_1\":\"first.\",\"computation_2\":\"second.\"},"
                + "\"conclusion\":\"c\",\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.findings()).isEqualTo("first.\n\nsecond.");
    }

    // ==========================================================================
    // Phase 7 F2 — formatFindingsForPrompt produces the labeled per-computation
    // layout the audit / professor / revision prompts feed to the LLM.
    // ==========================================================================

    @Test
    void formatFindingsForPromptSingleBlockReturnsProseVerbatim() {
        NarrationResult r = new NarrationResult(
                "h", "single prose.", "c", null, "ai",
                0.5, List.of(), null);
        assertThat(ResearchNarrationService.formatFindingsForPrompt(r))
                .isEqualTo("single prose.");
    }

    @Test
    void formatFindingsForPromptMultiBlockRendersLabeledLayout() {
        java.util.Map<Integer, String> m = new java.util.LinkedHashMap<>();
        m.put(2, "second prose.");
        m.put(1, "first prose.");
        NarrationResult r = new NarrationResult(
                "h", m, "c", null, "ai",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null, null);
        assertThat(ResearchNarrationService.formatFindingsForPrompt(r))
                .isEqualTo("[Computation 1]\nfirst prose.\n\n[Computation 2]\nsecond prose.");
    }

    @Test
    void formatFindingsForPromptDropsBlankBlocks() {
        java.util.Map<Integer, String> m = new java.util.LinkedHashMap<>();
        m.put(1, "first.");
        m.put(2, "   ");
        m.put(3, "third.");
        NarrationResult r = new NarrationResult(
                "h", m, "c", null, "ai",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null, null);
        assertThat(ResearchNarrationService.formatFindingsForPrompt(r))
                .isEqualTo("[Computation 1]\nfirst.\n\n[Computation 3]\nthird.");
    }

    // ==========================================================================
    // parseRefinementDecision
    // ==========================================================================

    @Test
    void refinementRefineActionReturnsDecision() {
        String json = "{\"action\":\"refine\",\"param1\":55,\"param2\":4.5,"
                + "\"rationale\":\"Test boundary near previous peak\"}";
        var result = ResearchNarrationService.parseRefinementDecision(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(55);
        assertThat(result.param2()).isEqualTo(4.5);
        assertThat(result.rationale()).contains("boundary");
    }

    @Test
    void refinementDoneActionReturnsNull() {
        String json = "{\"action\":\"done\"}";
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementRejectsOutOfRangeParams() {
        // param2=10.0 is above the 9.0 ceiling
        String json = "{\"action\":\"refine\",\"param1\":50,\"param2\":10.0,"
                + "\"rationale\":\"explore extreme scale\"}";
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementRejectsNegativeParam1() {
        String json = "{\"action\":\"refine\",\"param1\":-5,\"param2\":3.0,"
                + "\"rationale\":\"negative region\"}";
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementBoundaryValuesAreAccepted() {
        String json10_1 = "{\"action\":\"refine\",\"param1\":10,\"param2\":1.0,"
                + "\"rationale\":\"minimum values\"}";
        String json99_9 = "{\"action\":\"refine\",\"param1\":99,\"param2\":9.0,"
                + "\"rationale\":\"maximum values\"}";
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, json10_1)).isNotNull();
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, json99_9)).isNotNull();
    }

    @Test
    void refinementReturnsNullForNullInput() {
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, null)).isNull();
    }

    @Test
    void refinementReturnsNullForBlankInput() {
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, "   ")).isNull();
    }

    @Test
    void refinementReturnsNullForNonJson() {
        assertThat(ResearchNarrationService.parseRefinementDecision(MAPPER, "The investigation is complete.")).isNull();
    }

    @Test
    void refinementWithPreambleExtractsJson() {
        String response = "Let me think... {\"action\":\"refine\",\"param1\":67,"
                + "\"param2\":2.5,\"rationale\":\"narrow to high-yield sub-region\"}";
        var result = ResearchNarrationService.parseRefinementDecision(MAPPER, response);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(67);
    }

    @Test
    void refinementRationaleDefaultsToEmptyStringWhenAbsent() {
        String json = "{\"action\":\"refine\",\"param1\":50,\"param2\":3.0}";
        var result = ResearchNarrationService.parseRefinementDecision(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.rationale()).isEmpty();
    }

    // ==========================================================================
    // Paper ID pattern (used inside parseNarrationResult)
    // ==========================================================================

    @Test
    void paperIdPatternAcceptsValidIds() {
        // Test via parseNarrationResult: valid IDs should appear in the output
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,"
                + "\"cited_paper_ids\":[\"P-a29f-1\",\"P-ABCD-999\",\"P-0000-0\"]}";
        List<String> ids = ResearchNarrationService.parseNarrationResult(MAPPER, json).citedPaperIds();
        assertThat(ids).containsExactlyInAnyOrder("P-a29f-1", "P-ABCD-999", "P-0000-0");
    }

    @Test
    void paperIdPatternRejectsIdsMissingPrefix() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,"
                + "\"cited_paper_ids\":[\"a29f-1\",\"paper-a29f-1\"]}";
        assertThat(ResearchNarrationService.parseNarrationResult(MAPPER, json).citedPaperIds()).isEmpty();
    }
}
