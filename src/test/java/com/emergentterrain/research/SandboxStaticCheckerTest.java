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
    void memberAccess_isRejectedAtPreScan() {
        // Phase 19 Layer 1a — `Math.<builtin>` is now auto-rewritten before
        // parse, so we pick a different member-access shape to pin the
        // rejection path. `obj.method` (non-Math identifier) still goes
        // through preScanForJavascriptIdioms and rejects.
        String code = "return arr.someMethod();";
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

    // ── Phase 19 Layer 2 — per-rejection rewrite hints ──────────────────────

    @Test
    void rewriteHint_memberAccessPush_pointsAtFixedSizeArray() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): member access not supported: '.push' — the safe grammar has no objects.");
        assertThat(hint).startsWith("Quick fix:");
        assertThat(hint).contains("fixed-size");
        assertThat(hint).contains("xs[n] = v");
    }

    @Test
    void rewriteHint_memberAccessLength_pointsAtLenBuiltin() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): member access not supported: '.length' — …");
        assertThat(hint).contains("len(arr)");
    }

    @Test
    void rewriteHint_memberAccessMap_pointsAtForLoop() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): member access not supported: '.map' — …");
        assertThat(hint).contains("for (var i = 0");
        assertThat(hint).contains("len(arr)");
    }

    @Test
    void rewriteHint_mathPrefix_pointsAtFlatIdentifier() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): member access not supported: '.sqrt' — …");
        assertThat(hint).contains("drop the `Math.` prefix");
        assertThat(hint).contains("sqrt");
    }

    @Test
    void rewriteHint_objectLiteral_pointsAtRecordSample() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): object literals `{key: value}` not supported — …");
        assertThat(hint).contains("recordSample");
        assertThat(hint).contains("return x");
    }

    @Test
    void rewriteHint_multiVarComma_pointsAtOnePerLine() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): parse failed: Expected EQ got ',' line 7");
        assertThat(hint).contains("one variable per statement");
        assertThat(hint).contains("var a = 1; var b = 2;");
    }

    @Test
    void rewriteHint_breakOutsideBlock_pointsAtBraces() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): parse failed: Expected LBRACE got 'break' line 14");
        assertThat(hint).contains("if (cond) { break; }");
    }

    @Test
    void rewriteHint_stringLiteral_pointsAtNoStrings() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): parse failed: Unexpected char '\"' line 26");
        assertThat(hint).contains("no strings");
        assertThat(hint).contains("//");
    }

    @Test
    void rewriteHint_letDeclaration_pointsAtVar() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): parse failed: Unexpected token 'let' at line 3");
        assertThat(hint).contains("use `var`");
    }

    @Test
    void rewriteHint_nonWhitelistedFn_pointsAtBuiltinList() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): non-whitelisted function(s): foo, bar");
        assertThat(hint).contains("builtin");
        assertThat(hint).contains("function");
    }

    @Test
    void rewriteHint_shadowsBuiltin_pointsAtRename() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): user function 'abs' shadows a builtin");
        assertThat(hint).contains("rename");
        assertThat(hint).contains("myAbs");
    }

    @Test
    void rewriteHint_unknownReason_returnsEmpty() {
        // Reasons that don't match a known pattern fall back to the generic
        // helpText() — the rewrite-hint layer must NOT invent a fix.
        assertThat(SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): some new error mode no one has seen yet"))
                .isEmpty();
        assertThat(SandboxStaticChecker.rewriteHintFor("")).isEmpty();
        assertThat(SandboxStaticChecker.rewriteHintFor(null)).isEmpty();
    }

    // ── Phase 19 Layer 1a — Math.<builtin> auto-rewrite ─────────────────────

    @Test
    void mathPrefix_sqrt_isApproved() {
        // The LLM emits `Math.sqrt(x)` from its JS training corpus; the
        // sandbox already exposes `sqrt` as a flat builtin. Rewrite strips
        // the prefix before parse so the checker approves.
        String code = """
                var r = Math.sqrt(start * start + end * end);
                return r;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void mathPrefix_PI_isApproved() {
        String code = """
                var circumference = 2 * Math.PI * start;
                return circumference;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void mathPrefix_multipleInOneLine_isApproved() {
        String code = """
                var d = Math.sqrt(Math.pow(end - start, 2) + Math.pow(end, 2));
                return d;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void mathPrefix_nonBuiltin_stillRejected() {
        // Math.random is not in the sandbox builtin set (deterministic-only).
        // The rewrite leaves it alone so the member-access pre-scan rejects.
        String code = "return Math.random() * 100;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("member access");
    }

    @Test
    void mathPrefix_nonMathIdentifierMemberAccess_stillRejected() {
        // Only `Math.<name>` is rewritten by Layer 1a (and `.length` by
        // Layer 1b). `.push` was added as a supported method (paper-review
        // 2026-05-22). Other member access (e.g. `.pop`, `.toFixed`,
        // `.shift`) still hits the existing member-access guidance.
        String code = """
                var arr = [0, 0, 0];
                arr.pop();
                return arr[0];
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("member access");
    }

    @Test
    void mathPrefix_substringNotMatched() {
        // `myMath.sqrt` must NOT be rewritten — the rewrite is anchored to
        // a word boundary so it only fires on the exact `Math` identifier.
        String code = "return myMath.sqrt(x);";
        // Either the rewrite leaves it alone (so it gets rejected as
        // member-access) — that's the expected outcome.
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    // ── Phase 19 Layer 1b — <ident>.length auto-rewrite ────────────────────

    @Test
    void arrLength_inForLoopCondition_isApproved() {
        // The JS reflex `for (var i=0; i<arr.length; i++)` is the most
        // common .length use in the corpus. Must rewrite cleanly.
        String code = """
                var xs = [start, start+1, start+2, start+3];
                var s = 0;
                for (var i = 0; i < xs.length; i++) {
                  s = s + xs[i];
                }
                return s;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void arrLength_asScalarAssignment_isApproved() {
        String code = """
                var xs = [1, 2, 3];
                var n = xs.length;
                return n;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void arrLength_mathLength_stillRejected() {
        // Math doesn't have a length property even in JS — leave it as
        // member access so the diagnostic fires.
        String code = "return Math.length;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("member access");
    }

    @Test
    void arrLength_substringNotMatched() {
        // `mylength` shouldn't trigger; the rewrite is anchored to `.length`
        // with a word boundary. Code that uses `mylength` as a var name has
        // no member access at all — should be approved.
        String code = """
                var mylength = 10;
                return mylength;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void arrLength_rewriteIsSemanticsPreserving() {
        assertThat(SandboxStaticChecker.rewriteArrayLength("var n = xs.length;"))
                .isEqualTo("var n = len(xs);");
        assertThat(SandboxStaticChecker.rewriteArrayLength("for (var i=0; i<arr.length; i++) {}"))
                .isEqualTo("for (var i=0; i<len(arr); i++) {}");
        // Math.length — untouched.
        assertThat(SandboxStaticChecker.rewriteArrayLength("return Math.length;"))
                .isEqualTo("return Math.length;");
        // mylength as a var — untouched (no `.length` substring at all).
        assertThat(SandboxStaticChecker.rewriteArrayLength("var mylength = 10;"))
                .isEqualTo("var mylength = 10;");
        // No `.length` at all — fast path returns same string.
        assertThat(SandboxStaticChecker.rewriteArrayLength("return len(xs);"))
                .isEqualTo("return len(xs);");
        // Null safety.
        assertThat(SandboxStaticChecker.rewriteArrayLength(null)).isNull();
    }

    @Test
    void normalizeForSandbox_appliesBothRewrites() {
        // Combined pipeline test — Math.* AND .length in the same snippet.
        String input = "var n = Math.sqrt(xs.length);";
        assertThat(SandboxStaticChecker.normalizeForSandbox(input))
                .isEqualTo("var n = sqrt(len(xs));");
    }

    // ── Phase 19 Layer 1c — return-position object-literal rewrite ─────────

    @Test
    void p678_rlcObjectLiteralReturn_isApproved() {
        // Exact shape from P-a328-678 RLC call_4 (the case rejected by
        // today's grammar). After the rewrite, the LLM gets a working
        // tool call with Rcrit as the primary scalar and the other two
        // values surfaced via recordSample aggregates.
        String code = """
                var L = 0.0009;
                var C = 1.52e-6;
                var Rcrit = 2*sqrt(L/C);
                var omega0 = 1/sqrt(L*C);
                var Qcrit = omega0*L / Rcrit;
                return {Rcrit:Rcrit, omega0:omega0, Qcrit:Qcrit};
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void returnObjectLiteral_multiLine_isApproved() {
        String code = """
                var a = 1;
                var b = 2;
                return {
                  first: a,
                  second: b
                };
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void returnObjectLiteral_valuesWithSubcalls_isApproved() {
        // Commas inside sub-calls must NOT be treated as object-literal
        // separators. The splitter respects paren depth.
        String code = """
                return {x: pow(2, 3), y: sqrt(5)};
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void objectLiteralInNonReturnPosition_stillRejected() {
        // Only return-position object literals are rewritten. A `var x =
        // {a: 1};` style assignment must still hit the existing
        // object-literal pre-scan reject.
        String code = """
                var x = {a: 1, b: 2};
                return x;
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        assertThat(((SandboxStaticChecker.Result.Rejected) r).reason())
                .contains("object literals");
    }

    @Test
    void returnObjectLiteral_nestedBraces_fallsThroughToReject() {
        // Nested braces in the body cause the simple regex to miss; the
        // existing reject path fires. This is the conservative no-regression
        // guarantee.
        String code = "return {outer: {inner: 1}};";
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void returnObjectLiteral_rewriteIsSemanticsPreserving() {
        // Single value: recordSample(v); return v;
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral(
                "return {x: 5};"))
                .isEqualTo("recordSample(5); return 5;");

        // Multiple flat values: recordSample(v) for each, return first.
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral(
                "return {a: 1, b: 2, c: 3};"))
                .isEqualTo("recordSample(1); recordSample(2); recordSample(3); return 1;");

        // Sub-call values with commas inside parens: split respects depth.
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral(
                "return {x: pow(2, 3), y: sqrt(5)};"))
                .isEqualTo("recordSample(pow(2, 3)); recordSample(sqrt(5)); return pow(2, 3);");

        // No object literal in return: untouched.
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral("return 42;"))
                .isEqualTo("return 42;");

        // Fast path: no `return` and no `{` — same string back.
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral("var x = 5;"))
                .isEqualTo("var x = 5;");

        // Null safety.
        assertThat(SandboxStaticChecker.rewriteReturnObjectLiteral(null)).isNull();
    }

    @Test
    void normalizeForSandbox_appliesAllThreeRewrites() {
        // Pipeline test — Math.*, .length, AND object-literal return in one snippet.
        String input = "return {n: Math.sqrt(xs.length), m: Math.PI};";
        assertThat(SandboxStaticChecker.normalizeForSandbox(input))
                .isEqualTo("recordSample(sqrt(len(xs))); recordSample(PI); return sqrt(len(xs));");
    }

    // ── Phase 19 Layer 1c (array variant) — return-position array-literal rewrite ─────

    @Test
    void returnArrayLiteral_multiValue_isApproved() {
        // Companion of the object-literal case for the same multi-value
        // return pattern: `return [mean, std, n];` — the runtime rejects
        // ArrayLit as a sub-expression, the rewrite turns it into a
        // recordSample sequence plus a scalar return.
        String code = """
                var a = 1;
                var b = 2;
                var c = 3;
                return [a, b, c];
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void returnArrayLiteral_multiLine_isApproved() {
        String code = """
                var a = 1;
                var b = 2;
                return [
                  a,
                  b
                ];
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void returnArrayLiteral_valuesWithSubcalls_isApproved() {
        // Commas inside sub-calls must NOT be treated as array element
        // separators. The splitter respects paren depth.
        String code = """
                return [pow(2, 3), sqrt(5), atan2(1, 2)];
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void arrayLiteralInNonReturnPosition_stillRejected() {
        // Only return-position array literals are rewritten. A bare
        // `var x = ...;` with an array RHS is the existing Phase 10
        // declaration form and must keep its prior behaviour — which is
        // approved as a declaration here, NOT rewritten. Pin that the
        // rewrite leaves declaration RHS arrays alone.
        String input = "var xs = [1, 2, 3]; return xs[0];";
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(input))
                .isEqualTo(input);
    }

    @Test
    void returnArrayLiteral_nestedBrackets_rewriteMissesSafely() {
        // Nested brackets in the body cause the simple regex to miss; the
        // input is returned unchanged so the runtime's existing
        // ArrayLit-as-subexpression reject fires at execution. The
        // static checker is permissive about this AST shape (pre-existing
        // gap unrelated to Phase 19); the contract here is only that the
        // rewrite doesn't try to handle a shape it can't safely flatten.
        String input = "return [[1, 2], [3, 4]];";
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(input))
                .isEqualTo(input);
    }

    @Test
    void returnArrayLiteral_rewriteIsSemanticsPreserving() {
        // Single value: recordSample(v); return v;
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(
                "return [5];"))
                .isEqualTo("recordSample(5); return 5;");

        // Multiple flat values: recordSample(v) for each, return first.
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(
                "return [1, 2, 3];"))
                .isEqualTo("recordSample(1); recordSample(2); recordSample(3); return 1;");

        // Sub-call values with commas inside parens: split respects depth.
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(
                "return [pow(2, 3), sqrt(5)];"))
                .isEqualTo("recordSample(pow(2, 3)); recordSample(sqrt(5)); return pow(2, 3);");

        // No array literal in return: untouched.
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral("return 42;"))
                .isEqualTo("return 42;");

        // Var-decl array RHS: untouched (no `return` precedes the `[`).
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(
                "var xs = [1, 2, 3];"))
                .isEqualTo("var xs = [1, 2, 3];");

        // Fast path: no `return` and no `[` — same string back.
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral("var x = 5;"))
                .isEqualTo("var x = 5;");

        // Null safety.
        assertThat(SandboxStaticChecker.rewriteReturnArrayLiteral(null)).isNull();
    }

    @Test
    void normalizeForSandbox_appliesArrayReturnRewrite() {
        // Pipeline test — Math.*, .length, AND array-literal return composed.
        String input = "return [Math.sqrt(xs.length), Math.PI, 2*Math.E];";
        assertThat(SandboxStaticChecker.normalizeForSandbox(input))
                .isEqualTo("recordSample(sqrt(len(xs))); recordSample(PI); recordSample(2*E); "
                        + "return sqrt(len(xs));");
    }

    // ── Phase 19 Layer 1d — single-statement after if/while/for ────────────

    @Test
    void singleStmt_ifReturnWithoutBraces_isApproved() {
        // The JS reflex `if (cond) return x;` no longer requires braces.
        String code = """
                var x = start;
                if (x > 0) return x;
                return 0;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void singleStmt_ifAssignWithoutBraces_isApproved() {
        String code = """
                var x = 0;
                if (start > end) x = end;
                return x;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void singleStmt_ifElseWithoutBraces_isApproved() {
        String code = """
                var x = 0;
                if (start > end) x = start;
                else              x = end;
                return x;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void singleStmt_forBodyWithoutBraces_isApproved() {
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) s = s + i;
                return s;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void singleStmt_whileBodyWithoutBraces_isApproved() {
        String code = """
                var n = end;
                while (n > start) n = n - 1;
                return n;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void singleStmt_runsAndProducesCorrectValue() {
        // End-to-end: verify the runtime ALSO accepts the new form and
        // produces the right answer. SandboxEngine.run flows the same code
        // through the parser, so this is the integration check that the
        // parser change isn't only-static.
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) s = s + 1;
                if (s > 0) return s;
                return -1;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 4, 0, 0.0);
        assertThat(r.value()).isEqualTo(3.0);  // 4 - 1 = 3 iterations
    }

    // ── Phase 19 Layer 1e — break/continue as real keywords ────────────────

    @Test
    void breakInForLoop_exitsEarly() {
        // Static check passes, and runtime exits the loop at iteration 3.
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) {
                  if (i >= start + 3) break;
                  s = s + 1;
                }
                return s;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 100, 0, 0.0);
        assertThat(r.value()).isEqualTo(3.0);
    }

    @Test
    void continueInForLoop_skipsIteration() {
        // Sum odd indices in [0, 10): 1+3+5+7+9 = 25.
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) {
                  if (i % 2 == 0) continue;
                  s = s + i;
                }
                return s;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 10, 0, 0.0);
        assertThat(r.value()).isEqualTo(25.0);
    }

    @Test
    void breakInWhileLoop_exitsEarly() {
        String code = """
                var n = start;
                while (n < end) {
                  if (n >= start + 5) break;
                  n = n + 1;
                }
                return n;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1000, 0, 0.0);
        assertThat(r.value()).isEqualTo(5.0);
    }

    @Test
    void continueInWhileLoop_skipsToCondCheck() {
        // Count iterations where n is odd in [0, 10): 5.
        String code = """
                var n = start;
                var count = 0;
                while (n < end) {
                  n = n + 1;
                  if (n % 2 == 0) continue;
                  count = count + 1;
                }
                return count;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 10, 0, 0.0);
        assertThat(r.value()).isEqualTo(5.0);
    }

    @Test
    void breakWithSingleStmtBody_isApproved() {
        // The classic JS reflex: `if (cond) break;` — combines Layer 1d
        // (single-stmt body) with Layer 1e (break keyword).
        String code = """
                var n = start;
                while (n < end) {
                  if (n > start + 3) break;
                  n = n + 1;
                }
                return n;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 100, 0, 0.0);
        assertThat(r.value()).isEqualTo(4.0);  // 0, 1, 2, 3, 4 — break at n=4
    }

    @Test
    void breakOutsideLoop_throwsRuntimeError() {
        // Parser accepts it (no static depth tracking); runtime catches the
        // escaping signal and throws a clear error.
        String code = "break; return 0;";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("break used outside of a loop");
    }

    @Test
    void continueOutsideLoop_throwsRuntimeError() {
        String code = "continue; return 0;";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SandboxEngine.run(code, 0, 1, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("continue used outside of a loop");
    }

    // ── Phase 19 Layer 1f — comma-separated var declarations ───────────────

    @Test
    void commaSeparatedVarDecls_isApproved() {
        // The JS-idiom emission that today's grammar rejects with
        // "Expected EQ got ','" — appeared in the May-2026 corpus.
        String code = """
                var a = 1, b = 2, c = 3;
                return a + b + c;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isEqualTo(6.0);
    }

    @Test
    void commaSeparatedVarDecls_withSubcalls_isApproved() {
        // Commas inside expressions like `pow(2, 3)` must not split the
        // declaration list. Each value is parsed as a full expression
        // before the comma-decls loop sees the next comma.
        String code = """
                var a = pow(2, 3), b = sqrt(16);
                return a + b;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isEqualTo(12.0);  // 8 + 4
    }

    @Test
    void singleVarDecl_stillProducesPlainDecl() {
        // Regression: the existing single-decl path must keep returning
        // Stmt.Decl, not Stmt.Compound. Functional check via the runtime.
        String code = "var x = 42; return x;";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isEqualTo(42.0);
    }

    @Test
    void commaSeparatedVarDecls_insideLoopBody_isApproved() {
        // The declarations must work inside { ... } blocks, not just at
        // the top level — verifies execStmt unfolds Compound everywhere.
        String code = """
                var total = 0;
                for (var i = 0; i < end; i++) {
                  var u = i, v = i + 1;
                  total = total + u + v;
                }
                return total;
                """;
        // i=0: 0+1 → 1; i=1: 1+2 → 3; i=2: 2+3 → 5. Sum = 9.
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 3, 0, 0.0);
        assertThat(r.value()).isEqualTo(9.0);
    }

    @Test
    void nestedLoops_breakOnlyExitsInnerLoop() {
        // 3 outer iterations × (inner runs until break at j=2) = 3 × 2 = 6
        // outer iterations completed; sum of i values per inner break is 0+1+2.
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) {
                  for (var j = 0; j < 10; j++) {
                    if (j >= 2) break;
                    s = s + 1;
                  }
                }
                return s;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 3, 0, 0.0);
        assertThat(r.value()).isEqualTo(6.0);  // 3 outer × 2 inner
    }

    @Test
    void singleStmt_existingBraceFormStillWorks() {
        // Regression: the change must accept BOTH forms.
        String code = """
                var s = 0;
                for (var i = start; i < end; i++) {
                  s = s + i;
                }
                if (s > 0) {
                  return s;
                } else {
                  return -1;
                }
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void mathPrefix_rewriteIsSemanticsPreserving() {
        // Direct unit test on the helper: input → expected rewritten output.
        assertThat(SandboxStaticChecker.rewriteMathPrefix("Math.sqrt(4)"))
                .isEqualTo("sqrt(4)");
        assertThat(SandboxStaticChecker.rewriteMathPrefix("var r = Math.PI / 2;"))
                .isEqualTo("var r = PI / 2;");
        assertThat(SandboxStaticChecker.rewriteMathPrefix("Math.pow(Math.E, 2)"))
                .isEqualTo("pow(E, 2)");
        // Non-builtin: untouched.
        assertThat(SandboxStaticChecker.rewriteMathPrefix("Math.random()"))
                .isEqualTo("Math.random()");
        // No `Math.` at all: same string back (fast path).
        assertThat(SandboxStaticChecker.rewriteMathPrefix("return sqrt(x);"))
                .isEqualTo("return sqrt(x);");
        // Null safety.
        assertThat(SandboxStaticChecker.rewriteMathPrefix(null)).isNull();
    }

    // ── Phase 19 rollback flag — relaxed-grammar.enabled ──────────────────

    @Test
    void relaxedGrammarFlag_defaultsToTrue() {
        // Default must remain on — flag-off is the operator rollback path,
        // not the day-to-day behaviour.
        assertThat(SandboxStaticChecker.isRelaxedGrammarEnabled()).isTrue();
    }

    @Test
    void flagOff_skipsAllLayer1Rewrites() {
        boolean prior = SandboxStaticChecker.isRelaxedGrammarEnabled();
        try {
            SandboxStaticChecker.setRelaxedGrammarEnabled(false);
            // normalizeForSandbox is a no-op under flag-off: every Layer-1
            // input shape comes through unchanged.
            assertThat(SandboxStaticChecker.normalizeForSandbox("Math.sqrt(4)"))
                    .isEqualTo("Math.sqrt(4)");
            assertThat(SandboxStaticChecker.normalizeForSandbox("var n = xs.length;"))
                    .isEqualTo("var n = xs.length;");
            assertThat(SandboxStaticChecker.normalizeForSandbox("return {a: 1, b: 2};"))
                    .isEqualTo("return {a: 1, b: 2};");
            assertThat(SandboxStaticChecker.normalizeForSandbox("return [1, 2, 3];"))
                    .isEqualTo("return [1, 2, 3];");
        } finally {
            SandboxStaticChecker.setRelaxedGrammarEnabled(prior);
        }
    }

    @Test
    void flagOff_restoresPreScanRejectsOnRewrittenIdioms() {
        boolean prior = SandboxStaticChecker.isRelaxedGrammarEnabled();
        try {
            SandboxStaticChecker.setRelaxedGrammarEnabled(false);

            // Math.sqrt is approved under flag-on; under flag-off it reaches
            // the unchanged preScanForJavascriptIdioms which rejects on the
            // member-access pattern.
            SandboxStaticChecker.Result mathResult =
                    SandboxStaticChecker.check("return Math.sqrt(4);");
            assertThat(mathResult).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
            assertThat(((SandboxStaticChecker.Result.Rejected) mathResult).reason())
                    .contains("member access not supported");

            // Return-object-literal: approved under flag-on, rejected under
            // flag-off with the object-literal pre-scan message.
            SandboxStaticChecker.Result objResult = SandboxStaticChecker.check(
                    "return {a: 1, b: 2};");
            assertThat(objResult).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
            assertThat(((SandboxStaticChecker.Result.Rejected) objResult).reason())
                    .contains("object literals");
        } finally {
            SandboxStaticChecker.setRelaxedGrammarEnabled(prior);
        }
    }

    @Test
    void flagOn_preservesLayer1Behaviour() {
        // Sanity check that re-enabling the flag (after the off-path tests)
        // restores rewrite behaviour. Guards against accidental flag leak
        // between test cases.
        boolean prior = SandboxStaticChecker.isRelaxedGrammarEnabled();
        try {
            SandboxStaticChecker.setRelaxedGrammarEnabled(true);
            assertThat(SandboxStaticChecker.normalizeForSandbox("return Math.PI;"))
                    .isEqualTo("return PI;");
            assertThat(SandboxStaticChecker.check("return Math.sqrt(4);"))
                    .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
        } finally {
            SandboxStaticChecker.setRelaxedGrammarEnabled(prior);
        }
    }

    // ── Phase 20 Layer 1d — labelled string-concat return auto-rewrite ─────
    // The dominant static-parse rejection in the 2026-05-25 corpus: the LLM
    // returns a human-readable summary string, which the string-free grammar
    // killed at the lexer ("Unexpected char '\"'"). It should now rewrite to
    // recordSample(...) + scalar return and approve.

    @Test
    void labelledStringConcatReturn_isApproved() {
        // Verbatim shape from P-a328-928 (twin-prime constellation search).
        String code = """
                var totalPrimes = 0;
                var density = 0.0;
                for (var n = start; n < end; n++) { if (isPrime(n)) { totalPrimes = totalPrimes + 1; } }
                density = totalPrimes / (end - start);
                return "totalPrimes=" + totalPrimes + " density=" + density;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void labelledStringConcatReturn_rewritesToRecordSampleAndScalar() {
        String rewritten = SandboxStaticChecker.rewriteReturnStringConcat(
                "return \"max_dev=\" + maxDev + \" at a=\" + maxA + \" mean_dev=\" + meanDev;");
        assertThat(rewritten)
                .contains("recordSample(maxDev);")
                .contains("recordSample(maxA);")
                .contains("recordSample(meanDev);")
                .contains("return maxDev;")
                .doesNotContain("\"");
    }

    @Test
    void labelledStringConcatReturn_capturesLabelsAsNamedKeys() {
        SandboxStaticChecker.Normalized norm = SandboxStaticChecker.normalizeWithMetadata(
                "return \"totalPrimes=\" + totalPrimes + \" density=\" + density;");
        assertThat(norm.namedKeys()).containsExactly("totalPrimes", "density");
    }

    @Test
    void stringConcatReturn_withStringValuedTernary_isLeftToReject() {
        // A value segment that itself yields a string (ternary returning
        // quoted text) is ambiguous — the rewrite bails and the lexer reject
        // + Layer-2 hint fires, rather than recordSample()-ing a string.
        String code =
                "return \"ok? \" + (Math.abs(x - y) < 1 ? \"YES\" : \"NO\");";
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void returnOfBarePureString_isStillRejected() {
        // No numeric value to surface — nothing to rewrite, stays rejected.
        String code = "return \"done\";";
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }

    @Test
    void quoteInsideLineComment_doesNotTriggerRewriteAndStillApproves() {
        // A quote inside a // comment must not be treated as a string literal;
        // the comment is stripped by the lexer and the real return is numeric.
        String code = """
                // returns the body's "characteristic" ratio
                var r = end / start;
                return r;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void rewriteHint_stringLiteral_pointsAtRecordSampleForLabelledReturn() {
        String hint = SandboxStaticChecker.rewriteHintFor(
                "REJECTED (static): parse failed: Unexpected char '\"' line 4");
        assertThat(hint).contains("recordSample");
    }
}
