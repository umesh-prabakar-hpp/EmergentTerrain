package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 — pre-parse JavaScript-idiom detection tests for
 * {@link SandboxStaticChecker#preScanForJavascriptIdioms(String)}.
 *
 * <p>Targets the two dominant LLM mis-translation clusters surfaced in
 * the post-hang analyses doc §1.3:
 * <ul>
 *   <li>Member-access (43% of static-parse fails) — `.push`/`.length`/etc.</li>
 *   <li>Object literals (22%) — `{key: value}`</li>
 * </ul>
 *
 * <p>Each rejection now carries a specific message pointing to the safe-
 * grammar replacement (`recordSample(v)` for stats, fixed-size arrays for
 * collections, flat `Math.pow` → `pow`).
 */
class SandboxStaticCheckerPreScanTest {

    private static String rejectionReason(SandboxStaticChecker.Result r) {
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
        return ((SandboxStaticChecker.Result.Rejected) r).reason();
    }

    // ── Member-access detection ──────────────────────────────────────────────

    @Test
    void acceptsArrayPushOnIdentifier_paperReview_2026_05_22() {
        // Paper-review 2026-05-22 — `<ident>.push(<expr>)` is now supported
        // (dynamic-array append, capped at MAX_ARRAY_LEN). The pre-scan must
        // skip its rejection branch when the receiver is a bare identifier.
        // Real example from P-576: `gaps.push(n-prev)` — was rejected
        // pre-fix; should approve now and execute against SandboxArr.push.
        String code = "var gaps = [0]; gaps.push(5); return len(gaps);";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void detectsArrayLengthAccess() {
        String code = "var xs = [1, 2, 3]; return xs.length;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        String reason = rejectionReason(r);
        assertThat(reason).contains("length");
        assertThat(reason).contains("len(xs)");
    }

    @Test
    void detectsMathPrefix() {
        // LLM keeps emitting `Math.pow(2, 3)` instead of bare `pow(2, 3)`.
        String code = "return Math.pow(2, 3);";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        String reason = rejectionReason(r);
        assertThat(reason).contains("pow");
        assertThat(reason).contains("no `Math.` prefix");
    }

    @Test
    void detectsToFixed() {
        String code = "var x = 0.5; return x.toFixed(2);";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(rejectionReason(r)).contains("toFixed");
    }

    @Test
    void detectsArrayElementMemberAccess() {
        // Paper-review 2026-05-22 — `<ident>.push(v)` was unblocked, but
        // method-call-on-array-element (`gs[0].push(5)`) is still rejected
        // because we don't support nested arrays or methods on array
        // elements. The static-check skip only fires when the receiver
        // is a bare identifier; `]`-prefixed matches still reject.
        String code = "var gs = [0, 0]; gs[0].push(5); return 0;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(rejectionReason(r)).contains("push");
    }

    // ── Member-access — false-positive guards ────────────────────────────────

    @Test
    void floatLiteralNotRejected() {
        // `1.5` must NOT trigger — it's a valid numeric literal.
        String code = "var x = 1.5; return x * 2.7;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void leadingDotNumericNotRejected() {
        // `.5` style (leading-dot decimal) — also valid.
        String code = "var x = .5; return x;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void scientificNotationNotRejected() {
        // `1e-3`, `2.5e6` — exponent notation is a single number token.
        String code = "var h = 6.62607015e-34; var k = 1.380649e-23; return h * k;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    // ── Object-literal detection ─────────────────────────────────────────────

    @Test
    void detectsObjectLiteralReturn() {
        // Real example from P-577: `return {min: minSteps, max: maxSteps, mean: sum/count};`
        String code = """
                var n = 5;
                var s = 10;
                return {min: 1, max: n, mean: s};
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        String reason = rejectionReason(r);
        assertThat(reason).contains("object literal");
        assertThat(reason).contains("recordSample");
    }

    @Test
    void detectsInlineObjectLiteral() {
        String code = "var stats = {count: 0, sum: 0}; return stats;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(rejectionReason(r)).contains("object literal");
    }

    // ── Object-literal — false-positive guards ───────────────────────────────

    @Test
    void ifBlockBraceNotRejected() {
        // `if (c) { return 0; }` — brace opens a block, no identifier-colon
        // pattern follows.
        String code = "var n = 5; if (n > 0) { return 1; } return 0;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void forLoopBraceNotRejected() {
        String code = "for (var i = 0; i < 10; i = i + 1) { var x = i; } return 0;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void ternaryColonNotRejected() {
        // `cond ? a : b` — colon is not preceded by `{ ident`.
        String code = "var n = 5; return n > 0 ? 1 : 0;";
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void emptyCodeApproved() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms("");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void nullCodeApproved() {
        // Pre-scan is called from check() which handles null/blank separately;
        // pre-scan itself should not throw on null.
        SandboxStaticChecker.Result r = SandboxStaticChecker.preScanForJavascriptIdioms(null);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    // ── Full pipeline — end-to-end check() integration ───────────────────────

    @Test
    void checkEndToEndAcceptsArrayPush_paperReview_2026_05_22() {
        // Paper-review 2026-05-22 — `xs.push(1)` is now supported (dynamic
        // tail-append). The full check() pipeline must approve; the pre-scan
        // skips its rejection branch when the receiver is a bare identifier
        // and `.push` is the method.
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "var xs = [0]; xs.push(1); return 0;");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void checkEndToEndRejectsNonPushMemberAccessWithSpecificMessage() {
        // Other `.method` calls (e.g. `.pop`, `.toFixed`, `.shift`) still
        // get the pre-scan's instructive rejection — not the lexer's
        // generic "Unexpected char '.'". `.length` is excluded from this
        // check because Layer 1b auto-rewrites it to `len(xs)`.
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "var xs = [1, 2, 3]; xs.pop(); return 0;");
        String reason = rejectionReason(r);
        assertThat(reason).contains("member access");
        assertThat(reason).contains("pop");
        assertThat(reason).doesNotContain("Unexpected char");
    }

    @Test
    void checkEndToEndRejectsObjectLiteralWithSpecificMessage() {
        // Phase 19 Layer 1c — return-position object literals now rewrite
        // to recordSample(...) sequences. Use a non-return-position object
        // literal (var assignment) to pin the original intent that the
        // rejection message is specific, not the generic lexer error.
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "var pair = {min: 1, max: 5}; return pair;");
        String reason = rejectionReason(r);
        assertThat(reason).contains("object literal");
        assertThat(reason).doesNotContain("Unexpected token");
    }

    @Test
    void checkEndToEndApprovesLegitimateCode() {
        // Real shape from P-557 call_5 (Schrödinger): h/(2√(2kmT))
        String code = """
                var h = 6.62607015e-34;
                var m = 9.1093837e-31;
                var k = 1.380649e-23;
                var T = 300;
                var L = h / (2 * sqrt(2 * k * m * T));
                return L;
                """;
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(code);
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }
}
