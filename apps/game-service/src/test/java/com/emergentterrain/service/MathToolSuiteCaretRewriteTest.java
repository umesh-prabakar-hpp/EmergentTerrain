package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 21 §21.5 — pins the {@code ^} → {@code **} pre-rewrite on the
 * {@code evaluate_symbolic} and {@code run_gpu_eval} tool paths.
 *
 * <p>Pre-21.5: P-a328-685 wasted two rounds on {@code n^2} rejected by the
 * SandboxEngine tokenizer ("Unexpected char '^'"). The rewrite is
 * symmetric to Phase 19's sandbox-friction layer, applied at the tool
 * call boundary so the LLM never sees the lexer error.
 */
class MathToolSuiteCaretRewriteTest {

    @Test
    void rewriteCaretToStarStar_noChangeWhenAbsent() {
        assertThat(MathToolSuite.rewriteCaretToStarStar("n + 1", "test"))
                .isEqualTo("n + 1");
    }

    @Test
    void rewriteCaretToStarStar_handlesSingleHit() {
        assertThat(MathToolSuite.rewriteCaretToStarStar("n^2", "test"))
                .isEqualTo("n**2");
    }

    @Test
    void rewriteCaretToStarStar_handlesMultipleHits() {
        assertThat(MathToolSuite.rewriteCaretToStarStar("n^2 + n^3 + n^4", "test"))
                .isEqualTo("n**2 + n**3 + n**4");
    }

    @Test
    void rewriteCaretToStarStar_nullPassesThrough() {
        assertThat(MathToolSuite.rewriteCaretToStarStar(null, "test")).isNull();
    }

    @Test
    void evaluateSymbolic_caretFormulaSucceedsAfterRewrite() throws Exception {
        // Pre-21.5: SandboxEngine would throw "Unexpected char '^'" and the
        // tool would surface "Symbolic evaluation failed: …"; the LLM lost
        // a round. Post-21.5: the rewrite normalises before the lexer sees
        // the formula, so the call returns a real evaluation.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "evaluate_symbolic",
                "arguments", java.util.Map.of(
                        "expression",  "n^2",
                        "range_start", 1,
                        "range_end",   5,
                        "rationale",   "n squared over [1,5)"
                )));

        String result = executor.apply(toolCall);
        // The result string should be the summary form, NOT an error.
        assertThat(result).startsWith("Symbolic evaluation of f(n)=");
        assertThat(result).doesNotContain("Symbolic evaluation failed");
        assertThat(result).doesNotContain("Unexpected char");
    }

    @Test
    void evaluateSymbolic_starStarStillWorks() throws Exception {
        // Regression: an LLM that already emits the canonical `**` form
        // must continue to work — the rewrite must be idempotent on input
        // that has no `^`.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "evaluate_symbolic",
                "arguments", java.util.Map.of(
                        "expression",  "n**2",
                        "range_start", 1,
                        "range_end",   5,
                        "rationale",   "smoke"
                )));

        String result = executor.apply(toolCall);
        assertThat(result).startsWith("Symbolic evaluation of f(n)=");
        assertThat(result).doesNotContain("failed");
    }

    private static PaperDraft stubDraft() {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Prime Constellation Density",
                "Test", "raw", "h", "c", "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
