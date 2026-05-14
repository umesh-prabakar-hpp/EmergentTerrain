package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 Tier 4 regression — the Java-side static check that mirrors the
 * Rust {@code eval_expr::validate}. Two responsibilities:
 *
 * <ol>
 *   <li>Approve any expression the Rust validator would also accept, so the
 *       LLM doesn't see a Java rejection followed by a successful manual
 *       gRPC call.</li>
 *   <li>Reject everything that lives outside the strict subset — statements,
 *       user-function syntax, unknown identifiers, unknown functions,
 *       overlong formulas — with messages the LLM can act on.</li>
 * </ol>
 *
 * <p>The function whitelist symmetry with Rust is pinned by
 * {@link #allowListMatchesRustSpec}. When you add a function on one side,
 * update both.
 */
class GpuEvalStaticCheckerTest {

    // ── Allow list ───────────────────────────────────────────────────────────

    @Test
    void approvesBareConstants() {
        for (String f : new String[] { "n", "pi", "e", "1.5", "2.5e-3", ".5" }) {
            assertThat(GpuEvalStaticChecker.check(f))
                    .as(f).isInstanceOf(GpuEvalStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void approvesArithmeticAndPow() {
        for (String f : new String[] {
                "n + 1", "n - 1", "n * 2", "n / 2", "n % 3", "n ** 2", "(n + 1) * (n - 1)",
                "-n", "pi * n + e"
        }) {
            assertThat(GpuEvalStaticChecker.check(f))
                    .as(f).isInstanceOf(GpuEvalStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void approvesAllWhitelistedFunctions() {
        // One example per allow-listed function, with correct arity.
        String[] cases = {
                "sqrt(n)", "cbrt(n)", "log(n)", "log2(n)", "log10(n)", "exp(n)",
                "pow(n, 2)", "hypot(n, 1)",
                "sin(n)", "cos(n)", "tan(n)",
                "asin(n)", "acos(n)", "atan(n)", "atan2(n, 1)",
                "sinh(n)", "cosh(n)", "tanh(n)",
                "abs(n)", "sign(n)", "floor(n)", "ceil(n)", "round(n)",
                "min(n, 0)", "max(n, 0)"
        };
        for (String f : cases) {
            assertThat(GpuEvalStaticChecker.check(f))
                    .as(f).isInstanceOf(GpuEvalStaticChecker.Result.Approved.class);
        }
    }

    @Test
    void allowListMatchesRustSpec() {
        // Pin the size + key set of ALLOWED_FUNCTIONS. If you change this,
        // change the Rust ALLOWED_FNS too — and update this test.
        assertThat(GpuEvalStaticChecker.ALLOWED_FUNCTIONS).hasSize(25);
        assertThat(GpuEvalStaticChecker.ALLOWED_FUNCTIONS.keySet())
                .containsExactlyInAnyOrder(
                        "sqrt", "cbrt", "log", "log2", "log10", "exp", "pow", "hypot",
                        "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
                        "sinh", "cosh", "tanh", "abs", "sign",
                        "floor", "ceil", "round", "min", "max"
                );
        assertThat(GpuEvalStaticChecker.ALLOWED_VARS)
                .containsExactlyInAnyOrder("n", "pi", "e");
    }

    // ── Reject list ──────────────────────────────────────────────────────────

    @Test
    void rejectsUnknownIdentifier() {
        var r = GpuEvalStaticChecker.check("foo + 1");
        assertThat(r).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r).reason()).contains("foo");
    }

    @Test
    void rejectsSandboxOnlyBuiltins() {
        // These work in run_sandboxed_code but DON'T translate to f32 GPU.
        for (String f : new String[] {
                "isPrime(n)", "mobius(n)", "isSquarefree(n)", "mertensPartialSum(n)",
                "collatzSteps(n)", "sigmaSum(n)", "gcd(n, 2)"
        }) {
            var r = GpuEvalStaticChecker.check(f);
            assertThat(r)
                    .as(f).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void rejectsUnknownFunction() {
        var r = GpuEvalStaticChecker.check("zorp(n)");
        assertThat(r).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r).reason()).contains("zorp");
    }

    @Test
    void rejectsWrongArity() {
        var r1 = GpuEvalStaticChecker.check("pow(n)");
        assertThat(r1).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r1).reason())
                .contains("pow").contains("expected 2");

        var r2 = GpuEvalStaticChecker.check("sqrt(n, 1)");
        assertThat(r2).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r2).reason())
                .contains("sqrt").contains("expected 1");
    }

    @Test
    void rejectsStatementSyntax() {
        for (String f : new String[] {
                "var x = 5; return x",
                "for (var i = 0; i < 10; i++) { return i; }",
                "if (n > 0) { return n; } else { return 0; }",
                "return n",
                "function helper(x) { return x; }"
        }) {
            var r = GpuEvalStaticChecker.check(f);
            assertThat(r)
                    .as(f).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        }
    }

    @Test
    void rejectsAssignments() {
        // SandboxEngine's expression parser doesn't produce an Expr.Assign
        // when called via expr() (assignment is statement-level), so "n = 5"
        // parses as `n` with leftover tokens `= 5`. The leftover-token check
        // surfaces this as "unexpected token", which is good enough — the
        // important thing is that an assignment-shaped formula does NOT slip
        // through as a no-op `n`.
        var r = GpuEvalStaticChecker.check("n = 5");
        assertThat(r).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r).reason())
                .containsAnyOf("unexpected token", "assignment");
    }

    @Test
    void rejectsEmptyAndOverlong() {
        assertThat(GpuEvalStaticChecker.check("")).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(GpuEvalStaticChecker.check("   ")).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);

        String huge = "n + " + "1 + ".repeat(GpuEvalStaticChecker.MAX_FORMULA_LEN) + "0";
        var r = GpuEvalStaticChecker.check(huge);
        assertThat(r).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r).reason()).contains("too long");
    }

    @Test
    void rejectsMalformedExpression() {
        var r = GpuEvalStaticChecker.check("sqrt(");
        assertThat(r).isInstanceOf(GpuEvalStaticChecker.Result.Rejected.class);
        assertThat(((GpuEvalStaticChecker.Result.Rejected) r).reason())
                .contains("parse");
    }
}
