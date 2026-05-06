package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the four quality-pipeline static methods added to LlmService:
 *   parseNoveltyAssessment   — coverage assessment before formulation (Gap 1)
 *   buildStatisticalFramingPrompt / parseStatisticalFraming — precision reframing (Gaps 4 & 5)
 *   buildCritiquePrompt / parseCritiqueWeaknesses — self-critique before revision
 *   buildRevisionPrompt — revision prompt content
 *
 * All tests run without Spring, without LLM, without I/O — pure logic only.
 */
class LlmServiceQualityPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==========================================================================
    // parseNoveltyAssessment
    // ==========================================================================

    @Test
    void noveltyParsesValidJson() {
        String json = """
                {"novelty_score":0.6,"coverage_note":"low and mid param1 explored",
                "unexplored_hint":"try param1 near 80-90"}
                """;
        var result = LlmService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.score()).isEqualTo(0.6);
        assertThat(result.coverageNote()).contains("low and mid");
        assertThat(result.unexploredHint()).contains("80-90");
    }

    @Test
    void noveltyScoreClampedToZeroOneRange() {
        String json = """
                {"novelty_score":1.5,"coverage_note":"x","unexplored_hint":"y"}
                """;
        assertThat(LlmService.parseNoveltyAssessment(MAPPER, json).score()).isEqualTo(1.0);

        String json2 = """
                {"novelty_score":-0.3,"coverage_note":"x","unexplored_hint":"y"}
                """;
        assertThat(LlmService.parseNoveltyAssessment(MAPPER, json2).score()).isEqualTo(0.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnNullInput() {
        var result = LlmService.parseNoveltyAssessment(MAPPER, null);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.isSaturated()).isFalse();
    }

    @Test
    void noveltyReturnsFreshAssessmentOnBlankInput() {
        var result = LlmService.parseNoveltyAssessment(MAPPER, "   ");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnNonJson() {
        var result = LlmService.parseNoveltyAssessment(MAPPER, "Sure! The topic seems well-covered.");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnMalformedJson() {
        var result = LlmService.parseNoveltyAssessment(MAPPER, "{novelty_score: oops}");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyIsSaturatedBelowThreshold() {
        String json = """
                {"novelty_score":0.25,"coverage_note":"fully explored","unexplored_hint":""}
                """;
        var result = LlmService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.isSaturated()).isTrue();
    }

    @Test
    void noveltyIsNotSaturatedAboveThreshold() {
        String json = """
                {"novelty_score":0.31,"coverage_note":"mostly explored","unexplored_hint":"edge region"}
                """;
        var result = LlmService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.isSaturated()).isFalse();
    }

    @Test
    void noveltyMissingFieldsDefaultSafely() {
        var result = LlmService.parseNoveltyAssessment(MAPPER, "{}");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.coverageNote()).isEmpty();
        assertThat(result.unexploredHint()).isEmpty();
    }

    @Test
    void noveltyPreambleStrippedAndJsonExtracted() {
        String response = "Here is my assessment:\n{\"novelty_score\":0.4,\"coverage_note\":\"mid range\",\"unexplored_hint\":\"high end\"}";
        var result = LlmService.parseNoveltyAssessment(MAPPER, response);
        assertThat(result.score()).isEqualTo(0.4);
    }

    // ==========================================================================
    // buildStatisticalFramingPrompt
    // ==========================================================================

    @Test
    void framingPromptContainsDomainAndRawFindings() {
        String prompt = LlmService.buildStatisticalFramingPrompt(
                "Matrix yield σ_ym=200.0000 MPa, fibre strength σ_f=3000.000 MPa.",
                "MATERIALS", "composite-failure");
        assertThat(prompt).contains("MATERIALS").contains("composite-failure");
        assertThat(prompt).contains("σ_ym=200.0000");
    }

    @Test
    void framingPromptTruncatesVeryLongFindings() {
        String longFindings = "x".repeat(600);
        String prompt = LlmService.buildStatisticalFramingPrompt(longFindings, "PHYSICS", "pendulum");
        assertThat(prompt).contains("…");
        // Original 600-char finding should be truncated to 500; prompt not huge
        assertThat(prompt.length()).isLessThan(1500);
    }

    @Test
    void framingPromptInstructsNoPreamble() {
        String prompt = LlmService.buildStatisticalFramingPrompt("v=9.81 m/s^2", "PHYSICS", "gravity");
        assertThat(prompt).containsIgnoringCase("no preamble");
    }

    // ==========================================================================
    // parseStatisticalFraming
    // ==========================================================================

    @Test
    void framingParseReturnsCleanText() {
        String response = "The yield strength is approximately 200 MPa, within ±5%.";
        assertThat(LlmService.parseStatisticalFraming(response)).isEqualTo(response);
    }

    @Test
    void framingParseRejectsJson() {
        assertThat(LlmService.parseStatisticalFraming("{\"findings\":\"x\"}")).isNull();
    }

    @Test
    void framingParseRejectsNullAndBlank() {
        assertThat(LlmService.parseStatisticalFraming(null)).isNull();
        assertThat(LlmService.parseStatisticalFraming("   ")).isNull();
    }

    @Test
    void framingParseRejectsTooShortResponse() {
        assertThat(LlmService.parseStatisticalFraming("short")).isNull();
    }

    @Test
    void framingParseStripsHereIsPreamble() {
        String response = "Here is the rewritten findings:\nThe yield strength is approximately 200 MPa, within ±5%.";
        String result = LlmService.parseStatisticalFraming(response);
        assertThat(result).doesNotContain("Here is");
        assertThat(result).contains("approximately 200 MPa");
    }

    @Test
    void framingParseStripsHeresVariant() {
        String response = "Here's the revised text:\nThe fracture toughness is roughly 50 MPa·√m.";
        String result = LlmService.parseStatisticalFraming(response);
        assertThat(result).doesNotStartWith("Here");
        assertThat(result).contains("roughly 50");
    }

    @Test
    void framingParseStripsRewrittenPreamble() {
        String response = "Rewritten: σ_ym ≈ 200 MPa (±10%), consistent with the expected range.";
        String result = LlmService.parseStatisticalFraming(response);
        assertThat(result).doesNotStartWith("Rewritten");
        assertThat(result).contains("≈ 200 MPa");
    }

    // ==========================================================================
    // parseCritiqueWeaknesses
    // ==========================================================================

    @Test
    void critiqueParsesTwoWeaknesses() {
        String json = """
                {"weaknesses":["The conclusion overstates the finding","Missing uncertainty range"]}
                """;
        List<String> weaknesses = LlmService.parseCritiqueWeaknesses(MAPPER, json);
        assertThat(weaknesses).hasSize(2);
        assertThat(weaknesses.get(0)).contains("overstates");
        assertThat(weaknesses.get(1)).contains("uncertainty");
    }

    @Test
    void critiqueReturnsEmptyListForEmptyArray() {
        String json = "{\"weaknesses\":[]}";
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, json)).isEmpty();
    }

    @Test
    void critiqueReturnsEmptyOnNull() {
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, null)).isEmpty();
    }

    @Test
    void critiqueReturnsEmptyOnBlank() {
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, "  ")).isEmpty();
    }

    @Test
    void critiqueReturnsEmptyOnNonJson() {
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, "The draft looks fine.")).isEmpty();
    }

    @Test
    void critiqueParsesThreeWeaknesses() {
        String json = """
                {"weaknesses":["w1","w2","w3"]}
                """;
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, json)).hasSize(3);
    }

    @Test
    void critiqueFiltersBlankEntries() {
        String json = """
                {"weaknesses":["valid weakness","  ","another valid"]}
                """;
        List<String> weaknesses = LlmService.parseCritiqueWeaknesses(MAPPER, json);
        assertThat(weaknesses).hasSize(2);
        assertThat(weaknesses).doesNotContain("  ");
    }

    @Test
    void critiqueReturnsEmptyWhenMissingWeaknessesKey() {
        assertThat(LlmService.parseCritiqueWeaknesses(MAPPER, "{\"other\":\"field\"}")).isEmpty();
    }

    // ==========================================================================
    // buildCritiquePrompt
    // ==========================================================================

    @Test
    void critiquePromptIncludesHypothesisFindingsConclusion() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult(
                "hypothesis text", "findings text", "conclusion text", null, null, 0.8, List.of());
        String prompt = LlmService.buildCritiquePrompt(draft, narration);
        assertThat(prompt).contains("hypothesis text").contains("findings text").contains("conclusion text");
    }

    @Test
    void critiquePromptIncludesPreRegistrationWhenPresent() {
        PaperDraft draft = draftWithPreReg(new PreRegistration(
                "hyp", "value near 0.3", "no correlation", ""));
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        String prompt = LlmService.buildCritiquePrompt(draft, narration);
        assertThat(prompt).contains("value near 0.3");
    }

    @Test
    void critiquePromptIncludesClassification() {
        PaperDraft draft = minimalDraft(ResultClassification.NULL_RESULT);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        String prompt = LlmService.buildCritiquePrompt(draft, narration);
        assertThat(prompt).contains("NULL_RESULT");
    }

    // ==========================================================================
    // buildRevisionPrompt
    // ==========================================================================

    @Test
    void revisionPromptContainsWeaknesses() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.7, List.of());
        List<String> weaknesses = List.of("Over-claims the result", "Missing error bounds");
        String prompt = LlmService.buildRevisionPrompt(draft, narration, weaknesses);
        assertThat(prompt).contains("Over-claims the result");
        assertThat(prompt).contains("Missing error bounds");
    }

    @Test
    void revisionPromptForNullResultUsesNullConclusionSpec() {
        PaperDraft draft = minimalDraft(ResultClassification.NULL_RESULT);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        String prompt = LlmService.buildRevisionPrompt(draft, narration, List.of("overclaims"));
        assertThat(prompt).containsIgnoringCase("NOT confirmed");
    }

    @Test
    void revisionPromptForSupportedResultUsesOpenQuestionSpec() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.8, List.of());
        String prompt = LlmService.buildRevisionPrompt(draft, narration, List.of("minor gap"));
        assertThat(prompt).contains("open question");
    }

    @Test
    void revisionPromptContainsOriginalDraftContent() {
        PaperDraft draft = minimalDraft(ResultClassification.PARTIALLY_SUPPORTED);
        NarrationResult narration = new NarrationResult(
                "the original hypothesis", "the original findings", "the original conclusion",
                null, null, 0.6, List.of());
        String prompt = LlmService.buildRevisionPrompt(draft, narration, List.of("w1"));
        assertThat(prompt).contains("the original hypothesis");
        assertThat(prompt).contains("the original findings");
        assertThat(prompt).contains("the original conclusion");
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private static PaperDraft minimalDraft(ResultClassification classification) {
        return new PaperDraft(
                java.util.UUID.randomUUID(), "bot-1", "MATHEMATICS", "collatz-stopping-time",
                "A Test Paper", "raw findings here",
                "template hypothesis", "template conclusion",
                "THEORETICAL", List.of(),
                0.5, 0.5, 0.0,
                100L, Instant.now(), 1,
                "P-test-1", 42, 3.5,
                false, null, null,
                List.of(), null, null, classification);
    }

    private static PaperDraft draftWithPreReg(PreRegistration preReg) {
        return new PaperDraft(
                java.util.UUID.randomUUID(), "bot-1", "PHYSICS", "pendulum",
                "A Test Paper", "raw findings here",
                "template hypothesis", "template conclusion",
                "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0,
                100L, Instant.now(), 1,
                "P-test-2", 50, 2.0,
                false, null, null,
                List.of(), null, preReg, ResultClassification.SUPPORTED);
    }
}
