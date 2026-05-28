package com.emergentterrain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 29 §4.a — when {@code run_symbolic} rejects an expression for a
 * parse/syntax reason (unbalanced parens, etc.), the tool returns a targeted
 * correction message ("SYNTAX error … balance the parentheses … re-send")
 * instead of the misleading "only calculus/algebra/trig heads are allowed"
 * fallthrough, so the professor's next attempt fixes the expression rather than
 * bouncing through budget_fallback retries.
 */
class MathToolSuiteSyntaxCorrectionTest {

    @Test
    void unbalancedParensReturnsSyntaxCorrection() throws Exception {
        // null router (not mock(LlmRouter.class)) — run_symbolic uses Symja + the
        // static checker, never the router, and the dev-shell Mockito/JDK-25
        // friction can't inline-mock LlmRouter anyway.
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(Map.of(
                "name", "run_symbolic",
                "arguments", Map.of(
                        "expression", "2*(3+4",   // missing ')'
                        "rationale",  "syntax-error smoke")));

        String result = executor.apply(toolCall);

        assertThat(result).contains("could not be parsed");
        assertThat(result).contains("SYNTAX error");
        assertThat(result).containsIgnoringCase("parenthes");
        // must NOT misroute to the disallowed-head message
        assertThat(result).doesNotContain("only calculus/algebra/trig");
    }

    @Test
    void wellFormedExpressionIsNotFlaggedAsSyntaxError() throws Exception {
        // null router (not mock(LlmRouter.class)) — run_symbolic uses Symja + the
        // static checker, never the router, and the dev-shell Mockito/JDK-25
        // friction can't inline-mock LlmRouter anyway.
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(Map.of(
                "name", "run_symbolic",
                "arguments", Map.of(
                        "expression", "2*(3+4)",  // balanced
                        "rationale",  "well-formed control")));

        String result = executor.apply(toolCall);
        assertThat(result).doesNotContain("SYNTAX error");
        assertThat(result).doesNotContain("could not be parsed");
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
