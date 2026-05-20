package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.research.SymbolicEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 21 §21.3 — pins the solver-echo detection on
 * {@link MathToolSuite#runSymbolic}.
 *
 * <p>Before the fix: P-a328-693's only tool call was
 * {@code Solve[n2/n1 == Sin[theta_c] && n1 == 1.50 && n2 == 1.577, theta_c]};
 * Symja returned the input expression unevaluated (TIR with n₂ > n₁ has no
 * real solution); narration fabricated "61.04°" and "1.49" to fill the gap.
 *
 * <p>After the fix: the tool's run_symbolic path detects that Symja echoed
 * the input back without a numeric value, retries once with {@code N[…]},
 * and on continued failure returns an actionable error string the LLM sees
 * as a tool-failure signal — eliminating the opaque pass-through that
 * enabled the fabrication.
 */
class MathToolSuiteSolverEchoTest {

    // ── Unit tests on the static detector ────────────────────────────────

    @Test
    void leadingHead_extractsSolveFromBracketForm() {
        assertThat(MathToolSuite.leadingHead("Solve[x^2 - 4 == 0, x]")).isEqualTo("Solve");
    }

    @Test
    void leadingHead_extractsSolveFromParenForm() {
        assertThat(MathToolSuite.leadingHead("Solve(x^2 - 4 == 0, x)")).isEqualTo("Solve");
    }

    @Test
    void leadingHead_returnsNullForBareValue() {
        assertThat(MathToolSuite.leadingHead("3.14159")).isNull();
        assertThat(MathToolSuite.leadingHead("x + y")).isNull();
        assertThat(MathToolSuite.leadingHead("")).isNull();
        assertThat(MathToolSuite.leadingHead(null)).isNull();
    }

    @Test
    void isSolverEcho_trueWhenSolveEchoesWithoutNumeric() {
        // Synthesized Symja result: Solve echoed in full-form with no numeric.
        SymbolicEngine.SymbolicResult fake = new SymbolicEngine.SymbolicResult(
                "Solve[1.577/1.5 == Sin[theta_c], theta_c]",
                "Solve(1.577/1.5==Sin(theta_c),theta_c)",
                "",
                null,
                "");
        assertThat(MathToolSuite.isSolverEcho(fake.input(), fake)).isTrue();
    }

    @Test
    void isSolverEcho_falseWhenNumericIsPresent() {
        // Sin[1.0] DOES produce a numeric — not an echo even though the head is the same.
        SymbolicEngine.SymbolicResult fake = new SymbolicEngine.SymbolicResult(
                "Sin[1.0]",
                "0.841...",
                "",
                0.8414709848,
                "");
        // Sin isn't in ECHO_PRONE_HEADS anyway — double-guard test.
        assertThat(MathToolSuite.isSolverEcho(fake.input(), fake)).isFalse();
    }

    @Test
    void isSolverEcho_falseWhenSolveActuallyEvaluates() {
        // Solve[x^2-4==0, x] → {{x->-2}, {x->2}} — output does NOT start with "Solve".
        SymbolicEngine.SymbolicResult fake = new SymbolicEngine.SymbolicResult(
                "Solve[x^2-4==0, x]",
                "{{x->-2},{x->2}}",
                "",
                null,
                "");
        assertThat(MathToolSuite.isSolverEcho(fake.input(), fake)).isFalse();
    }

    @Test
    void isSolverEcho_falseForNonEchoProneHeads() {
        // Plus echoes its input when symbolic, but Plus isn't a "solver" head —
        // it's just algebraic. We don't want to flag e.g. `Plus[x, y]` → `x+y`.
        SymbolicEngine.SymbolicResult fake = new SymbolicEngine.SymbolicResult(
                "Plus[x, y]",
                "Plus(x,y)",
                "",
                null,
                "");
        assertThat(MathToolSuite.isSolverEcho(fake.input(), fake)).isFalse();
    }

    // ── End-to-end: actual Symja round-trip via runSymbolic ──────────────

    @Test
    void runSymbolic_unevaluableSolve_returnsActionableErrorNotEcho() throws Exception {
        // The exact P-a328-693 expression. Symja can't symbolically solve a
        // transcendental with all-numeric coefficients like this, so before
        // the fix the LLM saw the Solve call echoed back verbatim.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "run_symbolic",
                "arguments", java.util.Map.of(
                        "expression",
                        "Solve[n2/n1 == Sin[theta_c] && n1 == 1.50 && n2 == 1.577, theta_c]",
                        "rationale", "Find critical angle for TIR"
                )));
        String result = executor.apply(toolCall);

        // The result MUST NOT be the opaque "Symbolic result for … output=Solve(…)"
        // pass-through that enabled P-a328-693's fabrication.
        assertThat(result).doesNotStartWith("Symbolic result for");
        // It MUST be the actionable error so the LLM treats this as a tool failure.
        assertThat(result).contains("Symbolic solver did not evaluate");
        assertThat(result).contains("NSolve").contains("ArcSin");
    }

    @Test
    void runSymbolic_solvableExpression_stillReturnsNormalResult() throws Exception {
        // Regression — Sin[Pi/2] = 1 (numeric path). The echo detector must
        // not fire on results that actually compute. This pins the back-compat
        // behaviour for the 12+ existing Symja test cases.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "run_symbolic",
                "arguments", java.util.Map.of(
                        "expression", "Sin[Pi/2]",
                        "rationale", "smoke"
                )));
        String result = executor.apply(toolCall);

        assertThat(result).startsWith("Symbolic result for");
        assertThat(result).contains("numeric=1");
        assertThat(result).doesNotContain("Symbolic solver did not evaluate");
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private static PaperDraft stubDraft() {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "PHYSICS", "Snell Critical Angle Dispersion",
                "Test", "raw", "h", "c", "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
