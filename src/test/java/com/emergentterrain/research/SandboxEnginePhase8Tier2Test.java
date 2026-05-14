package com.emergentterrain.research;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 Tier 2 regression — user-defined function declarations inside
 * sandboxed code. Covers: declaration syntax, lexical scoping, recursion,
 * mutual recursion, the recursion-depth cap, builtin-shadow rejection,
 * and SandboxStaticChecker recognising user functions as legal call targets.
 *
 * <p>Companion to {@link SandboxEnginePhase8Tier1Test} (reflective Math.* +
 * SI constants + number-theory helpers).
 */
class SandboxEnginePhase8Tier2Test {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    // ── Basic declaration & dispatch ──────────────────────────────────────────

    @Test
    void singleArgumentFunctionRuns() {
        String code = "function dbl(x) { return x * 2; } return dbl(21);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(42.0, EPS);
    }

    @Test
    void multiArgumentFunctionRuns() {
        String code = "function hyp(a, b) { return sqrt(a * a + b * b); } "
                    + "return hyp(3, 4);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(5.0, EPS);
    }

    @Test
    void noArgumentFunctionRuns() {
        String code = "function answer() { return 42; } return answer();";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(42.0, EPS);
    }

    // ── Recursion ─────────────────────────────────────────────────────────────

    @Test
    void factorialRecursive() {
        String code = """
                function fact(n) {
                    if (n <= 1) { return 1; }
                    return n * fact(n - 1);
                }
                return fact(6);
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(720.0, EPS);
    }

    @Test
    void fibonacciNaiveRecursive() {
        // fib(15) = 610. Naive recursion blows up exponentially (~1973 calls)
        // but each call uses ~5 statements, comfortably under MAX_STMTS=50_000.
        String code = """
                function fib(n) {
                    if (n < 2) { return n; }
                    return fib(n - 1) + fib(n - 2);
                }
                return fib(15);
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(610.0, EPS);
    }

    @Test
    void mutualRecursionIsEvenIsOdd() {
        String code = """
                function isEven(n) { if (n == 0) { return 1; } return isOdd(n - 1); }
                function isOdd(n)  { if (n == 0) { return 0; } return isEven(n - 1); }
                return isEven(10);
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(1.0, EPS);
    }

    @Test
    void newtonRaphsonSquareRoot() {
        // sqrt(625) = 25 via Newton-Raphson on f(g) = g^2 - x.
        String code = """
                function nrSqrt(x, iters) {
                    var g = x / 2;
                    for (var i = 0; i < iters; i++) { g = (g + x / g) / 2; }
                    return g;
                }
                return nrSqrt(625, 20);
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(25.0, Offset.offset(1e-6));
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    void functionLocalsDoNotLeakToCallerScope() {
        // The outer `local` must remain 5 — the function's `local = 99` lives in
        // its own frame and is discarded on return.
        String code = """
                function f(x) { var local = 99; return local; }
                var local = 5;
                var unused = f(10);
                return local;
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(5.0, EPS);
    }

    @Test
    void functionCanReadGlobalConstants() {
        String code = "function area(r) { return PI * r * r; } return area(2);";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(Math.PI * 4, EPS);
    }

    @Test
    void parameterShadowsGlobalInsideFunctionOnly() {
        // Inside f, param PI=5 shadows the global PI. Outside, global PI is intact.
        String code = """
                function f(PI) { return PI; }
                var inside = f(5);
                return inside + PI;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isCloseTo(5.0 + Math.PI, EPS);
    }

    @Test
    void calleeDoesNotSeeCallerLocals() {
        // Lexical scoping: g sees only its own params and globals.
        // Caller's local `secret` must NOT leak into g — readVar of an unknown
        // name returns 0.0, so g returns 0.
        String code = """
                function g() { return secret; }
                function f() { var secret = 99; return g(); }
                return f();
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(0.0, EPS);
    }

    @Test
    void forLoopVarLandsInGlobalAfterTopLevelLoop() {
        // For-loops do NOT introduce a scope; the loop variable persists in the
        // surrounding scope (legacy semantic). Top-level `i` survives the loop.
        String code = "for (var i = 0; i < 3; i++) {} return i;";
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(3.0, EPS);
    }

    @Test
    void forLoopInsideFunctionStaysInFunctionFrame() {
        // The `i` inside the function lives in the function frame and does not
        // leak to caller — caller-side `i` defaults to 0.
        String code = """
                function loops() { for (var i = 0; i < 5; i++) {} return i; }
                var inside = loops();
                return inside - i;
                """;
        assertThat(SandboxEngine.run(code, 0, 1, 0, 0.0).value())
                .isCloseTo(5.0, EPS);
    }

    // ── Limits & error handling ──────────────────────────────────────────────

    @Test
    void recursionDepthCapTrips() {
        // Unbounded recursion must hit MAX_CALL_DEPTH before MAX_STMTS, with a
        // recognisable error message identifying the offending function.
        String code = "function f(n) { return f(n + 1); } return f(0);";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Call depth limit")
                .hasMessageContaining("f");
    }

    @Test
    void wrongArityIsRejected() {
        String code = "function add(a, b) { return a + b; } return add(1, 2, 3);";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("add")
                .hasMessageContaining("expects 2");
    }

    @Test
    void redefiningBuiltinIsRejected() {
        String code = "function sqrt(x) { return x; } return sqrt(9);";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("builtin")
                .hasMessageContaining("sqrt");
    }

    @Test
    void redefiningUserFunctionIsRejected() {
        String code = "function f(x) { return x; } function f(y) { return -y; } return f(3);";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already defined");
    }

    @Test
    void functionDeclarationInsideIfRejectedAtParseTime() {
        // function defs only allowed at top level — placing one inside an if
        // block must fail the parser, not silently semi-work.
        String code = "if (1) { function f() { return 0; } } return 0;";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("top level");
    }

    @Test
    void functionDeclarationInsideForRejectedAtParseTime() {
        String code = "for (var i = 0; i < 1; i++) { function f() { return 0; } } return 0;";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("top level");
    }

    @Test
    void unknownFunctionStillThrowsClearly() {
        String code = "function f(x) { return x; } return g(5);";
        assertThatThrownBy(() -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unknown function: g");
    }

    // ── Static-checker acceptance ────────────────────────────────────────────

    @Test
    void staticCheckerApprovesUserFunctionCalls() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check("""
                function dbl(x) { return x * 2; }
                function tpl(x) { return dbl(x) + x; }
                return tpl(7);
                """);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void staticCheckerRejectsBuiltinShadow() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "function isPrime(n) { return n; } return isPrime(7);");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("isPrime").contains("builtin");
    }

    @Test
    void staticCheckerRejectsDuplicateUserFunction() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "function f(x) { return x; } function f(y) { return -y; } return f(1);");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("redefined");
    }

    @Test
    void staticCheckerRejectsCallToUndefinedUserFunction() {
        // Calling a name that's neither a builtin nor a defined user function.
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "function f(x) { return helper(x); } return f(3);");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("helper");
    }
}
