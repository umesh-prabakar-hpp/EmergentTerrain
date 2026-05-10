package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.emergentterrain.service.LlmService.parseCritiqueResult;

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
        var result = FormulationService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.score()).isEqualTo(0.6);
        assertThat(result.coverageNote()).contains("low and mid");
        assertThat(result.unexploredHint()).contains("80-90");
    }

    @Test
    void noveltyScoreClampedToZeroOneRange() {
        String json = """
                {"novelty_score":1.5,"coverage_note":"x","unexplored_hint":"y"}
                """;
        assertThat(FormulationService.parseNoveltyAssessment(MAPPER, json).score()).isEqualTo(1.0);

        String json2 = """
                {"novelty_score":-0.3,"coverage_note":"x","unexplored_hint":"y"}
                """;
        assertThat(FormulationService.parseNoveltyAssessment(MAPPER, json2).score()).isEqualTo(0.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnNullInput() {
        var result = FormulationService.parseNoveltyAssessment(MAPPER, null);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.isSaturated()).isFalse();
    }

    @Test
    void noveltyReturnsFreshAssessmentOnBlankInput() {
        var result = FormulationService.parseNoveltyAssessment(MAPPER, "   ");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnNonJson() {
        var result = FormulationService.parseNoveltyAssessment(MAPPER, "Sure! The topic seems well-covered.");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyReturnsFreshAssessmentOnMalformedJson() {
        var result = FormulationService.parseNoveltyAssessment(MAPPER, "{novelty_score: oops}");
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noveltyIsSaturatedBelowThreshold() {
        String json = """
                {"novelty_score":0.25,"coverage_note":"fully explored","unexplored_hint":""}
                """;
        var result = FormulationService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.isSaturated()).isTrue();
    }

    @Test
    void noveltyIsNotSaturatedAboveThreshold() {
        String json = """
                {"novelty_score":0.31,"coverage_note":"mostly explored","unexplored_hint":"edge region"}
                """;
        var result = FormulationService.parseNoveltyAssessment(MAPPER, json);
        assertThat(result.isSaturated()).isFalse();
    }

    @Test
    void noveltyMissingFieldsDefaultSafely() {
        var result = FormulationService.parseNoveltyAssessment(MAPPER, "{}");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.coverageNote()).isEmpty();
        assertThat(result.unexploredHint()).isEmpty();
    }

    @Test
    void noveltyPreambleStrippedAndJsonExtracted() {
        String response = "Here is my assessment:\n{\"novelty_score\":0.4,\"coverage_note\":\"mid range\",\"unexplored_hint\":\"high end\"}";
        var result = FormulationService.parseNoveltyAssessment(MAPPER, response);
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
    // parseCritiqueResult — ExperimentalGap (param)
    // ==========================================================================

    @Test
    void critiqueResultParsesParamGap() {
        String json = """
                {"weaknesses":["overclaims"],"experimental_gap":{
                "type":"param","param1":75,"param2":5.0,
                "description":"test boundary","rationale":"address overclaim"}}
                """;
        var result = parseCritiqueResult(MAPPER, json);
        assertThat(result.weaknesses()).hasSize(1);
        assertThat(result.experimentalGap()).isNotNull();
        assertThat(result.experimentalGap().type()).isEqualTo("param");
        assertThat(result.experimentalGap().param1()).isEqualTo(75);
        assertThat(result.experimentalGap().param2()).isEqualTo(5.0);
        assertThat(result.experimentalGap().description()).contains("boundary");
        assertThat(result.experimentalGap().rationale()).contains("overclaim");
    }

    @Test
    void critiqueResultParsesDynamicGap() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"dynamic","predicate":"isPrime AND isHappy","range_start":200,"range_end":500,
                "metric":"fraction_satisfying","hypothesis":"roughly 4% are both","baseline":0.04,
                "description":"test prime+happy density","rationale":"claim needs more specificity"}}
                """;
        var result = parseCritiqueResult(MAPPER, json);
        assertThat(result.experimentalGap()).isNotNull();
        assertThat(result.experimentalGap().type()).isEqualTo("dynamic");
        assertThat(result.experimentalGap().predicate()).isEqualTo("isPrime AND isHappy");
        assertThat(result.experimentalGap().rangeStart()).isEqualTo(200);
        assertThat(result.experimentalGap().rangeEnd()).isEqualTo(500);
        assertThat(result.experimentalGap().metric()).isEqualTo("fraction_satisfying");
        assertThat(result.experimentalGap().baseline()).isEqualTo(0.04);
        assertThat(result.experimentalGap().hypothesis()).contains("4%");
    }

    @Test
    void critiqueResultRejectsParamGapWithInvalidParam1() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"param","param1":5,"param2":3.0,
                "description":"d","rationale":"r"}}
                """;
        assertThat(parseCritiqueResult(MAPPER, json).experimentalGap()).isNull();
    }

    @Test
    void critiqueResultRejectsDynamicGapWithInvalidPredicate() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"dynamic","predicate":"isGoldenRatio","range_start":1,"range_end":100,
                "metric":"fraction_satisfying","baseline":0.1,"description":"d","rationale":"r"}}
                """;
        assertThat(parseCritiqueResult(MAPPER, json).experimentalGap()).isNull();
    }

    @Test
    void critiqueResultRejectsDynamicGapWithRangeTooLarge() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"dynamic","predicate":"isPrime","range_start":1,"range_end":600,
                "metric":"fraction_satisfying","baseline":0.2,"description":"d","rationale":"r"}}
                """;
        assertThat(parseCritiqueResult(MAPPER, json).experimentalGap()).isNull();
    }

    @Test
    void critiqueResultRejectsDynamicGapWithInvalidMetric() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"dynamic","predicate":"isPrime","range_start":1,"range_end":100,
                "metric":"mean_of:goldbach","baseline":0.1,"description":"d","rationale":"r"}}
                """;
        assertThat(parseCritiqueResult(MAPPER, json).experimentalGap()).isNull();
    }

    @Test
    void critiqueResultNoGapWhenFieldOmitted() {
        String json = "{\"weaknesses\":[\"w1\"]}";
        var result = parseCritiqueResult(MAPPER, json);
        assertThat(result.weaknesses()).hasSize(1);
        assertThat(result.experimentalGap()).isNull();
        assertThat(result.hasActionableFeedback()).isTrue();
    }

    @Test
    void critiqueResultNotActionableWhenBothEmpty() {
        String json = "{\"weaknesses\":[]}";
        var result = parseCritiqueResult(MAPPER, json);
        assertThat(result.hasActionableFeedback()).isFalse();
    }

    @Test
    void critiqueResultActionableWithGapAloneAndNoWeaknesses() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"param","param1":80,"param2":4.0,
                "description":"test upper boundary","rationale":"fills gap"}}
                """;
        var result = parseCritiqueResult(MAPPER, json);
        assertThat(result.weaknesses()).isEmpty();
        assertThat(result.experimentalGap()).isNotNull();
        assertThat(result.hasActionableFeedback()).isTrue();
    }

    @Test
    void critiqueResultParsesOrPredicateInDynamicGap() {
        String json = """
                {"weaknesses":[],"experimental_gap":{
                "type":"dynamic","predicate":"isAbundant OR isPerfect","range_start":2,"range_end":302,
                "metric":"fraction_satisfying","baseline":0.24,"description":"abundant or perfect","rationale":"test density"}}
                """;
        var gap = parseCritiqueResult(MAPPER, json).experimentalGap();
        assertThat(gap).isNotNull();
        assertThat(gap.predicate()).isEqualTo("isAbundant OR isPerfect");
    }

    // ==========================================================================
    // buildCritiquePrompt — domain awareness
    // ==========================================================================

    @Test
    void critiquePromptForMathsDraftIncludesDynamicExperimentOption() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);  // MATHEMATICS, param1=42
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.8, List.of());
        String prompt = LlmService.buildCritiquePrompt(draft, narration);
        assertThat(prompt).contains("dynamic");
        assertThat(prompt).contains("isPrime");
        assertThat(prompt).contains("fraction_satisfying");
    }

    @Test
    void critiquePromptForDynamicPaperExcludesExperimentOption() {
        // Dynamic paper has param1 == null
        PaperDraft draft = dynamicPaperDraft();
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        String prompt = LlmService.buildCritiquePrompt(draft, narration);
        assertThat(prompt).contains("No additional computation is possible");
    }

    // ==========================================================================
    // buildRevisionPrompt — professor experiment section
    // ==========================================================================

    @Test
    void revisionPromptWithExperimentContainsProfessorSection() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.7, List.of());
        String prompt = LlmService.buildRevisionPrompt(
                draft, narration, List.of("w1"),
                "Professor-directed experiment — \"test at 80\":\nfraction=0.42 ± 0.03\n(Rationale: boundary)");
        assertThat(prompt).contains("PROFESSOR-PRESCRIBED EXPERIMENT");
        assertThat(prompt).contains("test at 80");
        assertThat(prompt).contains("fraction=0.42");
        assertThat(prompt).contains("incorporate it naturally");
    }

    @Test
    void revisionPromptWithoutExperimentOmitsProfessorSection() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.7, List.of());
        String prompt = LlmService.buildRevisionPrompt(draft, narration, List.of("w1"), null);
        assertThat(prompt).doesNotContain("PROFESSOR-PRESCRIBED");
        assertThat(prompt).doesNotContain("Professor-directed");
    }

    @Test
    void revisionPromptWithEmptyWeaknessesAndExperimentNotes() {
        PaperDraft draft = minimalDraft(ResultClassification.SUPPORTED);
        NarrationResult narration = new NarrationResult("h", "f", "c", null, null, 0.8, List.of());
        String prompt = LlmService.buildRevisionPrompt(
                draft, narration, List.of(),
                "Professor-directed experiment — \"boundary test\":\nresult=0.7\n(Rationale: boundary)");
        assertThat(prompt).contains("no textual weaknesses");
        assertThat(prompt).contains("PROFESSOR-PRESCRIBED EXPERIMENT");
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private static PaperDraft dynamicPaperDraft() {
        return new PaperDraft(
                java.util.UUID.randomUUID(), "bot-1", "MATHEMATICS", "collatz-stopping-time",
                "A Dynamic Paper", "dynamic findings here",
                "template hypothesis", "template conclusion",
                "THEORETICAL", List.of(),
                0.5, 0.5, 0.0,
                100L, Instant.now(), 1,
                "P-test-dyn", null, null,   // param1=null → dynamic paper
                false, null, null,
                List.of(), null, null, ResultClassification.SUPPORTED);
    }

    private static PaperDraft minimalDraft(ResultClassification classification) {
        return new PaperDraft(
                java.util.UUID.randomUUID(), "bot-1", "MATHEMATICS", "Collatz Stopping Time",
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
