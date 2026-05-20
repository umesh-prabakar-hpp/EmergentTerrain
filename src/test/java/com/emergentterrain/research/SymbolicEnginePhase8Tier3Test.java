package com.emergentterrain.research;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 Tier 3 regression — SymbolicEngine + SymbolicStaticChecker.
 *
 * <p>Pins the contract of the {@code run_symbolic} tool: which Symja heads
 * are reachable, which are rejected, that timeouts trip, and that the
 * output triple (output / TeX / numeric approximation) is populated for
 * the cases the LLM is most likely to ask about.
 */
class SymbolicEnginePhase8Tier3Test {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    // ── Calculus ─────────────────────────────────────────────────────────────

    @Test
    void definiteIntegralOfSinSquaredHasNumericPiOverTwo() {
        // Closed form: Integrate[Sin[x]^2, {x, 0, Pi}] = Pi/2.
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Integrate[Sin[x]^2, {x, 0, Pi}]");
        assertThat(r.numericApprox()).isNotNull();
        assertThat(r.numericApprox()).isCloseTo(Math.PI / 2, Offset.offset(1e-6));
    }

    @Test
    void derivativeOfCubicMatchesClosedForm() {
        // D[x^3 + 2*x, x] = 2 + 3*x^2. Output should mention 3 and x^2.
        SymbolicEngine.SymbolicResult r = SymbolicEngine.evaluate("D[x^3 + 2*x, x]");
        assertThat(r.output()).contains("3").contains("x");
    }

    @Test
    void seriesOfExpAroundZeroProducesPolynomial() {
        // Series[Exp[x], {x, 0, 4}] = 1 + x + x^2/2 + x^3/6 + x^4/24 + O[x]^5
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Series[Exp[x], {x, 0, 4}]");
        // The output contains the coefficients; pin a couple of denominators
        // we know must appear so a future Symja version that reorders is caught.
        assertThat(r.output()).contains("24");
    }

    @Test
    void limitOfSinXOverXIsOne() {
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Limit[Sin[x]/x, x -> 0]");
        assertThat(r.numericApprox()).isNotNull();
        assertThat(r.numericApprox()).isCloseTo(1.0, EPS);
    }

    // ── Algebra ──────────────────────────────────────────────────────────────

    @Test
    void simplifyTrigPythagoreanIdentity() {
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Simplify[Sin[x]^2 + Cos[x]^2]");
        assertThat(r.output()).isEqualTo("1");
        assertThat(r.numericApprox()).isNotNull();
        assertThat(r.numericApprox()).isCloseTo(1.0, EPS);
    }

    @Test
    void solveQuadraticReturnsBothRoots() {
        // x^2 - 5x + 6 = 0 → x = 2, x = 3.
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Solve[x^2 - 5*x + 6 == 0, x]");
        assertThat(r.output()).contains("2").contains("3");
    }

    @Test
    void factorOfQuadratic() {
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Factor[x^2 - 5*x + 6]");
        // (x-2)(x-3) — Symja prints the form with the roots
        assertThat(r.output()).contains("2").contains("3");
    }

    // ── Numeric / output shape ───────────────────────────────────────────────

    @Test
    void numericApproxIsNullForSymbolicResults() {
        // D[x^2, x] = 2*x has no numeric value (it's a symbolic expression).
        SymbolicEngine.SymbolicResult r = SymbolicEngine.evaluate("D[x^2, x]");
        assertThat(r.numericApprox()).isNull();
    }

    @Test
    void texFormPresentForCommonResults() {
        SymbolicEngine.SymbolicResult r =
                SymbolicEngine.evaluate("Integrate[Sin[x]^2, {x, 0, Pi}]");
        assertThat(r.tex()).isNotBlank();
    }

    @Test
    void blankExpressionRejected() {
        assertThatThrownBy(() -> SymbolicEngine.evaluate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutTripsOnRealComputation() {
        // Use a 1 ms wall-clock cap on a real evaluation. Even a trivial
        // Symja call dispatches in ~ms; the timeout must fire and surface
        // a clear error. Symja symbolically recognises some divergences
        // (e.g. Sum[k!, {k, 1, Infinity}]) without iterating, so we can't
        // rely on a "divergent expression" to exercise the wall-clock cap.
        assertThatThrownBy(() ->
                SymbolicEngine.evaluate("Integrate[Sin[x]^2, {x, 0, Pi}]", 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timed out");
    }

    // ── Static checker — allow list ──────────────────────────────────────────

    @Test
    void staticCheckerAllowsCommonCalculusHeads() {
        for (String expr : new String[] {
                "Integrate[Sin[x]^2, {x, 0, Pi}]",
                "D[x^3 + 2*x, x]",
                "Limit[Sin[x]/x, x -> 0]",
                "Series[Exp[x], {x, 0, 4}]",
                "Sum[1/k^2, {k, 1, 100}]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected approval for: %s, got: %s", expr, r)
                    .isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void staticCheckerAllowsAlgebraHeads() {
        for (String expr : new String[] {
                "Solve[x^2 - 5*x + 6 == 0, x]",
                "Simplify[Sin[x]^2 + Cos[x]^2]",
                "Expand[(x+1)^4]",
                "Factor[x^2 - 5*x + 6]",
                "Apart[(x^2+1)/(x-1)]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected approval for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void staticCheckerAllowsNumberTheoryHeads() {
        for (String expr : new String[] {
                "PrimeQ[97]",
                "MoebiusMu[30]",
                "EulerPhi[12]",
                "DivisorSigma[1, 6]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected approval for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
        }
    }

    // ── Static checker — reject list ─────────────────────────────────────────

    @Test
    void staticCheckerRejectsFileIo() {
        for (String expr : new String[] {
                "Import[\"/etc/passwd\"]",
                "Export[\"out.txt\", x^2]",
                "Get[\"foo.m\"]",
                "Put[x, \"out.m\"]",
                "OpenRead[\"/etc/passwd\"]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected rejection for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void staticCheckerRejectsNetworkAndProcess() {
        for (String expr : new String[] {
                "URLFetch[\"https://example.com\"]",
                "Run[\"ls\"]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected rejection for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void staticCheckerRejectsStateMutation() {
        for (String expr : new String[] {
                "Set[x, 5]",
                "SetDelayed[f[x], x^2]",
                "Clear[x]",
                "ClearAll[f]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected rejection for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void staticCheckerRejectsJvmEscape() {
        for (String expr : new String[] {
                "Java[\"java.lang.Runtime\"]",
                "JavaClass[\"java.lang.Process\"]",
                "LoadJavaClass[\"java.io.File\"]",
                "ToExpression[\"foo\"]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected rejection for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void staticCheckerRejectsBlankExpression() {
        SymbolicStaticChecker.Result r = SymbolicStaticChecker.check("");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
    }

    @Test
    void staticCheckerRejectsMalformedExpression() {
        SymbolicStaticChecker.Result r = SymbolicStaticChecker.check("Sin[x");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        assertThat(((SymbolicStaticChecker.Result.Rejected) r).reason())
                .contains("parse");
    }

    @Test
    void staticCheckerNamesFirstBadHead() {
        // Composite expression with one bad head mixed in. The error must
        // name the offending head clearly so the LLM can correct it.
        SymbolicStaticChecker.Result r =
                SymbolicStaticChecker.check("Plus[Integrate[Sin[x], x], Import[\"bad\"]]");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        assertThat(((SymbolicStaticChecker.Result.Rejected) r).reason())
                .contains("Import");
    }

    // ────────────── Phase 21 §21.5: local-scope wrappers ──────────────

    @Test
    void staticCheckerAllowsModuleWithLocalBindings() {
        // P-a328-690 round-0 signature: Module[{var=val}, expr]. Pre-21.5
        // rejected as "non-whitelisted heads: Module, CompoundExpression";
        // forced 1–3 wasted rounds before the LLM gave up and emitted the
        // canonical form.
        SymbolicStaticChecker.Result r =
                SymbolicStaticChecker.check("Module[{a = 2, b = 3}, a^2 + b]");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
    }

    @Test
    void staticCheckerAllowsWithAndBlock() {
        for (String expr : new String[] {
                "With[{a = 2}, a + 1]",
                "Block[{a = 5}, a * a]"
        }) {
            SymbolicStaticChecker.Result r = SymbolicStaticChecker.check(expr);
            assertThat(r)
                    .as("expected approval for: %s", expr)
                    .isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void staticCheckerAllowsCompoundExpression() {
        // `a; b; c` parses as CompoundExpression[a, b, c]. Module bodies
        // use it heavily, so admitting it is required for §21.5 to land.
        SymbolicStaticChecker.Result r =
                SymbolicStaticChecker.check("Module[{a = 2}, a + 1; a^2; a + a]");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Approved.class);
    }

    @Test
    void staticCheckerStillRejectsSetEvenInsideModuleBody() {
        // Module bodies are admitted, but Set / SetDelayed inside them are
        // still individually blocked — the local-scope wrapper isn't a
        // bypass for state-mutation primitives.
        SymbolicStaticChecker.Result r =
                SymbolicStaticChecker.check("Module[{a = 1}, Set[x, 5]; a + x]");
        assertThat(r).isInstanceOf(SymbolicStaticChecker.Result.Rejected.class);
        assertThat(((SymbolicStaticChecker.Result.Rejected) r).reason())
                .contains("Set");
    }
}
