package com.emergentterrain.service;

import com.emergentterrain.llm.LlmCallContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-08 — provider usage-cap sentinel detection. When a subscription-backed
 * model exhausts its quota it returns HTTP 200 with a short "You've hit your
 * limit · resets <time>" body and no tool_calls; left untreated the tool loop
 * reads it as an empty trace and the narration stage suppresses the paper. This
 * locks the recogniser ({@link GenericOpenAICompatPool#isUsageLimitSentinel})
 * onto the real message while keeping it tight enough that ordinary prose — even
 * prose that discusses rate limits — does not trip it.
 */
class GenericOpenAICompatPoolUsageLimitTest {

    @Test
    void professorInitialRound_requiresAToolWhenTheSchemaIsPresent() {
        boolean required = LlmCallContext.withRole("professor-tools", () ->
                GenericOpenAICompatPool.requiresProfessorInitialToolCall(true, true));

        assertThat(required).isTrue();
    }

    @Test
    void professorContinuationAndOtherRoles_keepOptionalToolChoice() {
        boolean continuation = LlmCallContext.withRole("professor-tools", () ->
                GenericOpenAICompatPool.requiresProfessorInitialToolCall(true, false));
        boolean ordinary = LlmCallContext.withRole("panel-review", () ->
                GenericOpenAICompatPool.requiresProfessorInitialToolCall(true, true));

        assertThat(continuation).isFalse();
        assertThat(ordinary).isFalse();
    }

    @Test
    void realClaudeSubscriptionCapMessage_isDetected() {
        // The exact body observed in research/llm-traces on 2026-06-08.
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(
                "You've hit your limit · resets 4:10pm (America/New_York)")).isTrue();
    }

    @Test
    void capMessageWithSurroundingWhitespace_isDetected() {
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(
                "  You've hit your limit · resets 9:00am  ")).isTrue();
    }

    @Test
    void nullEmptyOrBlank_isNotDetected() {
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(null)).isFalse();
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel("")).isFalse();
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel("   ")).isFalse();
    }

    @Test
    void longProseDiscussingLimitsAndResets_isNotDetected() {
        // A real paper paragraph that names both phrases must NOT trip the
        // sentinel — the >200-char guard keeps the match on the short cap stub.
        String prose = "The conjecture's worst-margin witness shows the bound "
                + "did hit your limit of tolerance near x=212003, and we reset "
                + "the window parameters before the held-out sweep; the residual "
                + "structure suggests the linear form cannot track prime-gap growth "
                + "across the full sieve range without a logarithmic correction term.";
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(prose)).isFalse();
    }

    @Test
    void shortTextMissingEitherPhrase_isNotDetected() {
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(
                "The bound resets at the window boundary.")).isFalse();   // no "hit your limit"
        assertThat(GenericOpenAICompatPool.isUsageLimitSentinel(
                "You've hit your limit of patience.")).isFalse();         // no "reset"
    }
}
