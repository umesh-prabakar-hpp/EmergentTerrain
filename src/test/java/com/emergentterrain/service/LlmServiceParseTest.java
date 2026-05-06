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
        var result = LlmService.parseFormulationResult(MAPPER, json);
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
        assertThat(LlmService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam1Above99() {
        String json = """
                {"param1":100,"param2":3.0,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(LlmService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam2Below1() {
        String json = """
                {"param1":50,"param2":0.5,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(LlmService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationRejectsParam2Above9() {
        String json = """
                {"param1":50,"param2":9.5,"question":"q",
                "pre_registration":{"hypothesis":"h","prediction":"p","falsification_condition":"f","mapping_notes":""}}
                """;
        assertThat(LlmService.parseFormulationResult(MAPPER, json)).isNull();
    }

    @Test
    void formulationReturnsNullForBlankInput() {
        assertThat(LlmService.parseFormulationResult(MAPPER, null)).isNull();
        assertThat(LlmService.parseFormulationResult(MAPPER, "")).isNull();
        assertThat(LlmService.parseFormulationResult(MAPPER, "  ")).isNull();
    }

    @Test
    void formulationReturnsNullForNonJson() {
        assertThat(LlmService.parseFormulationResult(MAPPER, "Sorry, I cannot help with that.")).isNull();
    }

    @Test
    void formulationWithPreambleAndJsonExtractedCorrectly() {
        String response = "Sure, here is my choice:\n"
                + "{\"param1\":75,\"param2\":2.0,\"question\":\"Explore upper range\","
                + "\"pre_registration\":{\"hypothesis\":\"High param1 yields peaks\","
                + "\"prediction\":\"value > 200\",\"falsification_condition\":\"value < 100\","
                + "\"mapping_notes\":\"\"}}";
        var result = LlmService.parseFormulationResult(MAPPER, response);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(75);
    }

    @Test
    void formulationWithMissingPreRegistrationStillReturnsValidParams() {
        // LLM omits the pre_registration field — params are still valid
        String json = "{\"param1\":30,\"param2\":4.0,\"question\":\"No pre-reg\"}";
        var result = LlmService.parseFormulationResult(MAPPER, json);
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
        var result = LlmService.parseFormulationResult(MAPPER, json);
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
        assertThat(LlmService.parseFormulationResult(MAPPER, json10)).isNotNull();
        assertThat(LlmService.parseFormulationResult(MAPPER, json99)).isNotNull();
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
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, json);
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
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, json);
        assertThat(result.confidenceScore()).isEqualTo(0.5);
    }

    @Test
    void narrationClampsConfidenceScoreToRange() {
        String tooHigh = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":1.5,\"cited_paper_ids\":[]}";
        String tooLow  = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":-0.3,\"cited_paper_ids\":[]}";
        assertThat(LlmService.parseNarrationResult(MAPPER, tooHigh).confidenceScore()).isEqualTo(1.0);
        assertThat(LlmService.parseNarrationResult(MAPPER, tooLow).confidenceScore()).isEqualTo(0.0);
    }

    @Test
    void narrationFiltersMalformedPaperIds() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,"
                + "\"cited_paper_ids\":[\"P-a29f-10\",\"not-a-paper-id\",\"P-zzzz-5\","
                + "\"P-1234-99\"]}";
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, json);
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
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, json);
        assertThat(result.followUp()).isNull();
    }

    @Test
    void narrationDiscardsFollowUpPredictedScoreOutOfRange() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[],"
                + "\"followUp\":{\"param1\":50,\"param2\":3.0,\"predictedScore\":1.5}}";
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, json);
        // followUp itself is valid (params in range), but predictedScore is discarded
        assertThat(result.followUp()).isNotNull();
        assertThat(result.followUp().predictedScore()).isNull();
    }

    @Test
    void narrationReturnsEmptyDefaultForNullInput() {
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, null);
        assertThat(result.hypothesis()).isNull();
        assertThat(result.findings()).isNull();
        assertThat(result.conclusion()).isNull();
        assertThat(result.followUp()).isNull();
        assertThat(result.confidenceScore()).isEqualTo(0.5);
        assertThat(result.citedPaperIds()).isEmpty();
    }

    @Test
    void narrationReturnsEmptyDefaultForBlankInput() {
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, "   ");
        assertThat(result.confidenceScore()).isEqualTo(0.5);
        assertThat(result.citedPaperIds()).isEmpty();
    }

    @Test
    void narrationReturnsEmptyDefaultForMalformedJson() {
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, "not json at all");
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
        NarrationResult result = LlmService.parseNarrationResult(MAPPER, response);
        assertThat(result.hypothesis()).contains("Prime gaps");
        assertThat(result.confidenceScore()).isEqualTo(0.6);
    }

    @Test
    void narrationEmptyCitedPaperIdsArrayProducesEmptyList() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.7,\"cited_paper_ids\":[]}";
        assertThat(LlmService.parseNarrationResult(MAPPER, json).citedPaperIds()).isEmpty();
    }

    @Test
    void narrationAbsentFollowUpFieldProducesNullFollowUp() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,\"cited_paper_ids\":[]}";
        assertThat(LlmService.parseNarrationResult(MAPPER, json).followUp()).isNull();
    }

    // ==========================================================================
    // parseRefinementDecision
    // ==========================================================================

    @Test
    void refinementRefineActionReturnsDecision() {
        String json = "{\"action\":\"refine\",\"param1\":55,\"param2\":4.5,"
                + "\"rationale\":\"Test boundary near previous peak\"}";
        var result = LlmService.parseRefinementDecision(MAPPER, json);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(55);
        assertThat(result.param2()).isEqualTo(4.5);
        assertThat(result.rationale()).contains("boundary");
    }

    @Test
    void refinementDoneActionReturnsNull() {
        String json = "{\"action\":\"done\"}";
        assertThat(LlmService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementRejectsOutOfRangeParams() {
        // param2=10.0 is above the 9.0 ceiling
        String json = "{\"action\":\"refine\",\"param1\":50,\"param2\":10.0,"
                + "\"rationale\":\"explore extreme scale\"}";
        assertThat(LlmService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementRejectsNegativeParam1() {
        String json = "{\"action\":\"refine\",\"param1\":-5,\"param2\":3.0,"
                + "\"rationale\":\"negative region\"}";
        assertThat(LlmService.parseRefinementDecision(MAPPER, json)).isNull();
    }

    @Test
    void refinementBoundaryValuesAreAccepted() {
        String json10_1 = "{\"action\":\"refine\",\"param1\":10,\"param2\":1.0,"
                + "\"rationale\":\"minimum values\"}";
        String json99_9 = "{\"action\":\"refine\",\"param1\":99,\"param2\":9.0,"
                + "\"rationale\":\"maximum values\"}";
        assertThat(LlmService.parseRefinementDecision(MAPPER, json10_1)).isNotNull();
        assertThat(LlmService.parseRefinementDecision(MAPPER, json99_9)).isNotNull();
    }

    @Test
    void refinementReturnsNullForNullInput() {
        assertThat(LlmService.parseRefinementDecision(MAPPER, null)).isNull();
    }

    @Test
    void refinementReturnsNullForBlankInput() {
        assertThat(LlmService.parseRefinementDecision(MAPPER, "   ")).isNull();
    }

    @Test
    void refinementReturnsNullForNonJson() {
        assertThat(LlmService.parseRefinementDecision(MAPPER, "The investigation is complete.")).isNull();
    }

    @Test
    void refinementWithPreambleExtractsJson() {
        String response = "Let me think... {\"action\":\"refine\",\"param1\":67,"
                + "\"param2\":2.5,\"rationale\":\"narrow to high-yield sub-region\"}";
        var result = LlmService.parseRefinementDecision(MAPPER, response);
        assertThat(result).isNotNull();
        assertThat(result.param1()).isEqualTo(67);
    }

    @Test
    void refinementRationaleDefaultsToEmptyStringWhenAbsent() {
        String json = "{\"action\":\"refine\",\"param1\":50,\"param2\":3.0}";
        var result = LlmService.parseRefinementDecision(MAPPER, json);
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
        List<String> ids = LlmService.parseNarrationResult(MAPPER, json).citedPaperIds();
        assertThat(ids).containsExactlyInAnyOrder("P-a29f-1", "P-ABCD-999", "P-0000-0");
    }

    @Test
    void paperIdPatternRejectsIdsMissingPrefix() {
        String json = "{\"hypothesis\":\"h\",\"findings\":\"f\",\"conclusion\":\"c\","
                + "\"confidenceScore\":0.5,"
                + "\"cited_paper_ids\":[\"a29f-1\",\"paper-a29f-1\"]}";
        assertThat(LlmService.parseNarrationResult(MAPPER, json).citedPaperIds()).isEmpty();
    }
}
