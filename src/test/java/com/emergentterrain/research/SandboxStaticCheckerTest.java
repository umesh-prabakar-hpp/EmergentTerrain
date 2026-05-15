package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the deterministic sandbox safety check. Each test corresponds to either:
 *   (a) a real false-rejection from the May-2026 corpus run that the 4b LLM
 *       reviewer wrongly blocked, OR
 *   (b) a true-rejection that the static checker must catch on its own without
 *       deferring to an LLM.
 *
 * Together these prove the new pipeline does NOT regress on the four hallucination
 * cases observed in /tmp/et-app.log (Collatz "x-as-string-manip", sigmaSum
 * "not whitelisted", Newton-Raphson "rule 1+5 violation", bisection "may not
 * converge") AND still catches genuine policy violations.
 */
class SandboxStaticCheckerTest {

    // ── False-rejections from the May-2026 corpus that MUST now pass ─────────

    @Test
    void collatzBoundedLoop_isApproved() {
        // Real shape from /tmp/et-app.log line 156. The 4b reviewer hallucinated
        // "string manipulation (use of x as a variable name)". There's no x and
        // a Latin identifier is not string manipulation.
        String code = """
                var countTerminate = 0;
                var countTotal = 0;
                for (var n = start; n < end; n++) {
                  if (collatzTerminates(n)) { countTerminate++; }
                  countTotal++;
                }
                return countTerminate / countTotal;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void sigmaSumIsActuallyWhitelisted() {
        // Real shape from log line 158. The 4b reviewer claimed sigmaSum was
        // "not whitelisted" — but it's literally in SandboxEngine.BUILTINS.
        String code = """
                var total = 0;
                for (var n = start; n < end; n++) {
                  total += sigmaSum(n);
                }
                return total;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void newtonRaphsonBoundedIterations_isApproved() {
        // Real shape from log line 276. Newton-Raphson is an algorithm, not a
        // function call. Implemented with var/for/if/abs/pow — all whitelisted.
        String code = """
                var x = param2;
                for (var i = 0; i < 50; i++) {
                  var fx = x * x - param1;
                  var dfx = 2 * x;
                  x = x - fx / dfx;
                  if (abs(fx) < 0.0001) { return x; }
                }
                return x;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void bisectionWithSqrt_isApproved() {
        // Real shape from log line 281. Bisection IS the textbook convergent
        // root-finder. The 4b claim "may not converge" is mathematically false.
        String code = """
                var lo = 0.0;
                var hi = param2;
                for (var i = 0; i < 60; i++) {
                  var mid = (lo + hi) / 2.0;
                  if (mid * mid - param1 < 0) { lo = mid; } else { hi = mid; }
                }
                return (lo + hi) / 2.0;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    // ── True-rejections the static checker must still catch ─────────────────

    @Test
    void unknownFunction_isRejectedWithName() {
        String code = "return mySecretFunc(start);";
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason()).contains("mySecretFunc");
    }

    @Test
    void multipleUnknownFunctions_areAllReported() {
        String code = """
                var a = fooBar(1);
                var b = bazQux(2);
                return a + b;
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        String reason = ((SandboxStaticChecker.Result.Rejected) r).reason();
        assertThat(reason).contains("fooBar").contains("bazQux");
    }

    @Test
    void stringLiteral_isRejectedAtLexer() {
        // Lexer rejects double-quote: the entire string syntax is absent.
        String code = "var s = \"hello\"; return 0;";
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void memberAccess_isRejectedAtLexer() {
        // Math.sqrt would lex `Math`, then bare `.` which isn't tokenized
        // outside numeric literals; lexer throws.
        String code = "return Math.sqrt(param1);";
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void empty_isRejected() {
        assertThat(SandboxStaticChecker.check(""))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(SandboxStaticChecker.check("   \n  "))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void nullCode_isRejected() {
        assertThat(SandboxStaticChecker.check(null))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void deeplyNestedUnknownCall_isStillCaught() {
        // The walker must recurse into binops, ternaries, calls within calls.
        String code = "return min(abs(sqrt(unknownDeep(start))), 100);";
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason()).contains("unknownDeep");
    }

    // ── Phase 10 — relaxations: while, ===/!== ──────────────────────────────

    @Test
    void whileLoop_isApproved() {
        // `while` is bounded by the same MAX_ITERATIONS guard as `for`. Allowing
        // it removes the need to invent a counter variable when a fence condition
        // is more natural (e.g. convergence: while (abs(err) > 1e-9) { ... }).
        String code = """
                var x = param2;
                while (abs(x * x - param1) > 0.0001) {
                  x = (x + param1 / x) / 2;
                }
                return x;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void strictEquality_isApproved() {
        // === and !== are aliased to == and != at the lexer. The sandbox is
        // single-typed (double), so the loose/strict distinction is meaningless;
        // accepting both forms removes a parse-failure source for LLM code.
        String code = """
                var c = 0;
                for (var n = start; n < end; n++) {
                  if (n === 7) { c += 100; }
                  if (n !== 0) { c += 1; }
                }
                return c;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void whileWithUnknownCall_isStillCaught() {
        // The walker must descend into while-body statements.
        String code = """
                var i = 0;
                while (i < 10) {
                  i = i + unknownInsideWhile(i);
                }
                return i;
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason()).contains("unknownInsideWhile");
    }

    // ── Phase 10 — helpText() grammar reference ─────────────────────────────

    @Test
    void helpText_listsTheCriticalGrammarFeatures() {
        // The harness appends helpText() to every static-stage rejection sent
        // back to the LLM. The text must spell out the non-obvious differences
        // from JavaScript (no member access, no strings, arrays cap, etc.) so
        // the LLM doesn't retry with the same disallowed construct.
        String help = SandboxStaticChecker.helpText();
        assertThat(help).isNotBlank();
        // Common JS reaches that trip up sandbox code:
        assertThat(help).contains("NOT JavaScript");
        assertThat(help).contains("NOT supported");
        assertThat(help).contains("member access");
        // Phase 10 affordances the LLM should know to use:
        assertThat(help).contains("var xs = [");
        assertThat(help).contains("len(");
        assertThat(help).contains("recordSample");
        assertThat(help).contains("while");
        // Builtin highlights:
        assertThat(help).contains("pow").contains("sqrt").contains("collatzSteps");
        // Working example:
        assertThat(help).contains("Working example");
    }
}
