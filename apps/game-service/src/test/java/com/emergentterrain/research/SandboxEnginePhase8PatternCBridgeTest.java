package com.emergentterrain.research;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 Pattern C regression — bridge builtins inside SandboxEngine that
 * delegate to {@link SymbolicEngine} (Symja) for symbolic operations the
 * numeric sandbox can't do alone. Three bridges:
 *
 * <ul>
 *   <li>{@code integrate(expr, var, lo, hi)} — definite integral; numeric.</li>
 *   <li>{@code differentiate(expr, var, at)} — derivative evaluated at point.</li>
 *   <li>{@code simplify(expr)} — must reduce to a finite number.</li>
 * </ul>
 *
 * <p>Companion to {@link SandboxEnginePhase8Tier1Test} (Math.* + constants
 * + number-theory helpers), {@link SandboxEnginePhase8Tier2Test} (user
 * functions), and {@link SymbolicEnginePhase8Tier3Test} (the underlying
 * SymbolicEngine + SymbolicStaticChecker).
 */
class SandboxEnginePhase8PatternCBridgeTest {

    private static final Offset<Double> LOOSE = Offset.offset(1e-4);
    private static final Offset<Double> TIGHT = Offset.offset(1e-9);

    // ── Happy paths ──────────────────────────────────────────────────────────

    @Test
    void integrateSinSquaredOverZeroToPi() {
        // Closed form: Integrate[Sin[x]^2, {x, 0, Pi}] = Pi/2 ≈ 1.5708.
        String code = "return integrate(sin(x)**2, x, 0, PI);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(Math.PI / 2, LOOSE);
    }

    @Test
    void integrateXOverZeroToOneEqualsHalf() {
        // Integrate[x, {x, 0, 1}] = 1/2.
        String code = "return integrate(x, x, 0, 1);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(0.5, TIGHT);
    }

    @Test
    void differentiateCubicAtFive() {
        // d/dx[x^3 + 2x] = 3x^2 + 2. At x=5 → 3*25 + 2 = 77.
        String code = "return differentiate(x**3 + 2 * x, x, 5);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(77.0, TIGHT);
    }

    @Test
    void differentiateSinAtZero() {
        // d/dx[Sin[x]] at x=0 = Cos[0] = 1.
        String code = "return differentiate(sin(x), x, 0);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(1.0, TIGHT);
    }

    @Test
    void simplifyPythagoreanIdentity() {
        // Simplify[Sin[x]^2 + Cos[x]^2] = 1.
        String code = "return simplify(sin(x)**2 + cos(x)**2);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(1.0, TIGHT);
    }

    @Test
    void simplifyConstantFolding() {
        // Simplify[2 + 3*4] = 14.
        String code = "return simplify(2 + 3 * 4);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(14.0, TIGHT);
    }

    // ── Bridge can use sandbox globals (constants get substituted) ───────────

    @Test
    void integrateUsesPiConstantInBounds() {
        // PI sandbox-side becomes Pi Symja-side (substituted as exact 3.14159...).
        String code = "return integrate(cos(x), x, 0, PI);";
        // Integrate[Cos[x], {x, 0, Pi}] = Sin[Pi] - Sin[0] = 0.
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(0.0, LOOSE);
    }

    @Test
    void bridgeCallableFromInsideUserFunction() {
        String code = """
                function quadArea(lo, hi) {
                    return integrate(x**2, x, lo, hi);
                }
                return quadArea(0, 3);
                """;
        // Integrate[x^2, {x, 0, 3}] = 9.
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(9.0, TIGHT);
    }

    // ── Arg-shape rejection ─────────────────────────────────────────────────

    @Test
    void integrateWrongArityRejected() {
        assertThatThrownBy(() ->
                SandboxEngine.run("return integrate(sin(x), x, 0);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("integrate")
                .hasMessageContaining("4 args");
    }

    @Test
    void differentiateWrongArityRejected() {
        assertThatThrownBy(() ->
                SandboxEngine.run("return differentiate(x**2, x);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("differentiate")
                .hasMessageContaining("3 args");
    }

    @Test
    void simplifyWrongArityRejected() {
        assertThatThrownBy(() ->
                SandboxEngine.run("return simplify(x, 5);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simplify")
                .hasMessageContaining("1 arg");
    }

    @Test
    void integrationVariableMustBeBareIdentifier() {
        // Second arg to integrate must be a Var, not an expression.
        assertThatThrownBy(() ->
                SandboxEngine.run("return integrate(sin(x)**2, x + 1, 0, PI);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("integrate")
                .hasMessageContaining("bare identifier");
    }

    // ── Safety / scope rejection ─────────────────────────────────────────────

    @Test
    void bridgeCannotNestAnotherBridge() {
        // simplify wrapping integrate would smuggle a numeric back into the
        // symbolic translator. Reject — the LLM should chain them as sequential
        // statements instead.
        assertThatThrownBy(() ->
                SandboxEngine.run("return simplify(integrate(x, x, 0, 1));", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nest")
                .hasMessageContaining("integrate");
    }

    @Test
    void bridgeRejectsSandboxOnlyBuiltinInsideExpression() {
        // isPrime is sandbox-only — it has no Symja equivalent.
        assertThatThrownBy(() ->
                SandboxEngine.run("return integrate(isPrime(x), x, 0, 10);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("isPrime");
    }

    @Test
    void bridgeRejectsUnknownVariable() {
        // 'q' is neither the bound variable nor a sandbox global.
        assertThatThrownBy(() ->
                SandboxEngine.run("return integrate(sin(q), x, 0, PI);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unbound")
                .hasMessageContaining("q");
    }

    @Test
    void simplifyRejectsSymbolicResult() {
        // Simplify[2*x + 3] stays symbolic — no numeric coercion possible.
        assertThatThrownBy(() ->
                SandboxEngine.run("return simplify(2 * x + 3);", 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simplify");
        // Either "unbound variable x" or "non-numeric result" is acceptable — both
        // surface the LLM's mistake clearly; the test just pins that this case
        // does NOT silently return 0 or NaN.
    }

    // ── Static checker still accepts everything ──────────────────────────────

    @Test
    void staticCheckerAllowsBridgeNames() {
        // SandboxStaticChecker references SandboxEngine.BUILTINS directly, so
        // adding the bridge names to BUILTINS makes them automatically
        // acceptable at parse-time without needing a checker change.
        SandboxStaticChecker.Result r1 = SandboxStaticChecker.check(
                "return integrate(sin(x), x, 0, PI);");
        SandboxStaticChecker.Result r2 = SandboxStaticChecker.check(
                "return differentiate(x**2, x, 3);");
        SandboxStaticChecker.Result r3 = SandboxStaticChecker.check(
                "return simplify(sin(x)**2 + cos(x)**2);");
        assertThat(r1).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        assertThat(r2).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        assertThat(r3).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void bridgeNamesCannotBeShadowedByUserFunctions() {
        // Same rule as for other builtins: user functions cannot shadow.
        assertThatThrownBy(() -> SandboxEngine.run(
                "function integrate(a, b, c, d) { return 99; } return integrate(x, x, 0, 1);",
                0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("builtin")
                .hasMessageContaining("integrate");
    }
}
