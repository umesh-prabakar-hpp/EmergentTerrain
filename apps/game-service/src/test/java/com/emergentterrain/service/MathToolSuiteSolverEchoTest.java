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

    // ── 2026-05-21 follow-on — sandbox-primitive routing hint ───────────

    @Test
    void sandboxHint_detectsLowercaseSandboxPrimitiveInRejectReason() {
        // P-a328-831 round 0 rejected with: "non-whitelisted head(s): isprime, Optional"
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): isprime, Optional"))
                .isNotNull()
                .contains("isprime");

        // P-a328-843 round 2 rejected with: "non-whitelisted head(s): Optional, sigmasum"
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): Optional, sigmasum"))
                .isNotNull()
                .contains("sigmasum");
    }

    @Test
    void sandboxHint_detectsMultipleSandboxPrimitives() {
        // Hypothetical: LLM stuffs several lowercase predicates into one call.
        String hint = MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): isprime, ishappy, collatzsteps");
        assertThat(hint).contains("isprime").contains("ishappy").contains("collatzsteps");
    }

    @Test
    void sandboxHint_nullForUnrelatedRejection() {
        // Heads that ARE dangerous (I/O, process) — generic safety message
        // should fire, not the sandbox hint.
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): URLFetch")).isNull();
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): Set, SetDelayed")).isNull();
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "parse failed: unexpected token")).isNull();
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(null)).isNull();
    }

    @Test
    void sandboxHint_nullForGcdLog2WhichHaveMathematicaEquivalents() {
        // GCD and Log2 ARE in the symbolic whitelist with capitalized names,
        // so a sandbox hint would mis-direct the LLM. The check excludes
        // these primitive names from the sandbox-routing set.
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): gcd")).isNull();
        assertThat(MathToolSuite.sandboxPrimitiveHintFor(
                "non-whitelisted head(s): log2")).isNull();
    }

    // ── Phase 21 §21.3 follow-on — Julia-path echo detector ─────────────

    @Test
    void isJuliaEcho_trueWhenResultIsBareIdentifier() {
        // P-a328-839: `zeta(s) = sum(n^(-s) for n in 1:1000)` — Julia parsed
        // the `=` as a function definition; result is the bare identifier.
        var er = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "zeta", "typeof(zeta)", 12L);
        assertThat(MathToolSuite.isJuliaEcho(
                "zeta(s) = sum(n^(-s) for n in 1:1000)", er)).isTrue();

        // P-a328-823: `(let theta = ...; series = ...; series)` — Symbolics
        // didn't simplify; result is the type name.
        var er2 = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "Series", "Symbolics.Num", 8L);
        assertThat(MathToolSuite.isJuliaEcho(
                "(let theta = 22.85 * pi/180; series = 1 + sin(theta/2)^2/4; series)", er2)).isTrue();
    }

    @Test
    void isJuliaEcho_falseWhenResultIsNumeric() {
        // P-a328-831 happy path — twin density estimate.
        var er = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "16.388551535980042", "Float64", 5L);
        assertThat(MathToolSuite.isJuliaEcho(
                "2*0.66016*( (1002-462) ) / (log((462+1002)/2)^2 )", er)).isFalse();

        // P-a328-842 — floor result.
        var er2 = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "5.0", "Float64", 2L);
        assertThat(MathToolSuite.isJuliaEcho("floor(2.03e-6 / 400e-9)", er2)).isFalse();
    }

    @Test
    void isJuliaEcho_passesThroughLegitimateAtoms() {
        // Boolean evaluation — legitimate single-token result.
        var trueResult = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "true", "Bool", 1L);
        assertThat(MathToolSuite.isJuliaEcho("isprime(7)", trueResult)).isFalse();

        var falseResult = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "false", "Bool", 1L);
        assertThat(MathToolSuite.isJuliaEcho("isprime(8)", falseResult)).isFalse();

        // Inf / NaN are legitimate numeric atoms in Julia.
        var infResult = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "Inf", "Float64", 1L);
        assertThat(MathToolSuite.isJuliaEcho("1/0.0", infResult)).isFalse();

        var nanResult = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "NaN", "Float64", 1L);
        assertThat(MathToolSuite.isJuliaEcho("0.0/0.0", nanResult)).isFalse();
    }

    @Test
    void isJuliaEcho_falseForSymbolicResultsWithStructure() {
        // A Symbolics expression that returned a structured result (operators,
        // digits, or whitespace) is NOT an echo even if symbolic.
        var er = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "x^2 + 2*x + 1", "Symbolics.Num", 4L);
        assertThat(MathToolSuite.isJuliaEcho("(x + 1)^2", er)).isFalse();

        // Vector / tuple return.
        var er2 = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "[1, 2, 3]", "Vector{Int64}", 2L);
        assertThat(MathToolSuite.isJuliaEcho("[1, 2, 3]", er2)).isFalse();
    }

    @Test
    void isJuliaEcho_falseOnNullInputs() {
        var er = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "zeta", "typeof(zeta)", 1L);
        assertThat(MathToolSuite.isJuliaEcho(null, er)).isFalse();
        assertThat(MathToolSuite.isJuliaEcho("foo", null)).isFalse();
        var emptyEr = new com.emergentterrain.llm.JuliaMathClient.EvalResult(
                "", "Nothing", 1L);
        assertThat(MathToolSuite.isJuliaEcho("foo", emptyEr)).isFalse();
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
    void runSymbolic_lowercaseSandboxPrimitive_routesToSandboxHint() throws Exception {
        // P-a328-831 round 0 shape: LLM called `count(p -> isprime(p), 462:1001)`
        // through run_symbolic. The static-checker rejects `isprime`; the new
        // hint routes the LLM to run_sandboxed_code instead of bouncing
        // through budget_fallback retries.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), new ObjectMapper());
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "name", "run_symbolic",
                "arguments", java.util.Map.of(
                        "expression", "isprime(7) + isprime(11)",
                        "rationale", "twin density"
                )));
        String result = executor.apply(toolCall);

        assertThat(result).startsWith("REJECTED by safety review:");
        assertThat(result).contains("isprime");
        assertThat(result).contains("SandboxEngine number-theoretic primitives");
        assertThat(result).contains("run_sandboxed_code");
        assertThat(result).contains("PrimeQ");
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
