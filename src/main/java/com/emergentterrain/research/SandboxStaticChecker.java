package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic safety check for {@code run_sandboxed_code} pseudocode.
 *
 * <p>Reuses {@link SandboxEngine}'s real lexer and parser. The parser already
 * rejects strings, arrays, member access, imports, and class instantiation at
 * parse time (the lexer doesn't tokenize {@code "..."}, {@code [}, or {@code .}
 * outside numeric literals). The remaining static rule — every function call
 * must reference a name in {@link SandboxEngine#BUILTINS} — is enforced here by
 * walking the AST.
 *
 * <p>Replaces the prior 4b LLM "safety review" for whitelist + syntax compliance.
 * A May-2026 corpus run showed the 4b reviewer rejecting 100% of legitimate
 * sandbox attempts by hallucinating constraints (called a variable named {@code x}
 * "string manipulation", denied {@code sigmaSum} despite it being whitelisted,
 * rejected bisection as non-convergent). Deterministic checks remove that
 * failure mode entirely. The runtime interpreter still enforces
 * {@link SandboxEngine#MAX_ITERATIONS} and {@link SandboxEngine#MAX_STMTS} as
 * a safety backstop.
 */
public final class SandboxStaticChecker {

    private SandboxStaticChecker() {}

    /**
     * Phase 19 rollback flag — {@code app.research.sandbox.relaxed-grammar.enabled}.
     * When {@code true} (default), {@link #normalizeForSandbox(String)} applies
     * the Layer-1 pre-parse rewrites (Math.* → flat builtin, {@code .length} →
     * {@code len(...)}, return-position object / array literals → recordSample
     * sequences). When {@code false}, the rewrites are skipped and code reaches
     * {@link #preScanForJavascriptIdioms(String)} unchanged — restoring the
     * pre-Phase-19 stricter behaviour without removing the new code paths from
     * the binary. Process-wide; see phase-19-sandbox-friction.md Open Q2.
     *
     * <p>Volatile so a Spring {@code @Component} updating it at startup is
     * visible to subsequent {@link #check(String)} / {@link SandboxEngine#run}
     * calls without explicit synchronisation.
     */
    private static volatile boolean relaxedGrammarEnabled = true;

    /**
     * Set the relaxed-grammar flag (default {@code true}). Wired from
     * {@code app.research.sandbox.relaxed-grammar.enabled} in application.yml
     * by {@code SandboxFlagConfig} at startup. Visible for tests so the
     * flag-off path can be exercised without standing up Spring.
     */
    public static void setRelaxedGrammarEnabled(boolean enabled) {
        relaxedGrammarEnabled = enabled;
    }

    /** Read-only accessor for the relaxed-grammar flag. Mainly for tests. */
    public static boolean isRelaxedGrammarEnabled() {
        return relaxedGrammarEnabled;
    }

    public sealed interface Result {
        record Approved() implements Result {}
        record Rejected(String reason) implements Result {}
    }

    /**
     * Normalised form of a sandbox snippet plus the named-return metadata
     * the rewrite collected. {@code code} is what reaches the lexer / parser;
     * {@code namedKeys} is the list of key names from a return-position
     * object literal ({@code return {a:x, b:y};} → [{@code "a"},{@code "b"}])
     * or synthetic numeric keys from a return-position array literal
     * ({@code return [x, y, z];} → [{@code "0"},{@code "1"},{@code "2"}]).
     * Empty when no multi-value return was present.
     *
     * <p>The runtime uses {@code namedKeys.size()} as the "trailing
     * recordSample window" size — the last {@code k} recordSample values
     * executed pair up with these keys to populate
     * {@link SandboxEngine.SandboxResult#namedValues()} verbatim, without
     * conflating with user-supplied sample-loop {@code recordSample} calls
     * that came earlier in the run.
     */
    public record Normalized(String code, java.util.List<String> namedKeys) {}

    public static Result check(String code) {
        if (code == null || code.isBlank()) {
            return new Result.Rejected("empty code");
        }

        // Phase 19 Layer 1 — pre-parse rewrites: Math.<builtin> → flat
        // builtin (1a); <ident>.length → len(<ident>) (1b). Centralised in
        // normalizeForSandbox so the engine's runtime entry stays in lockstep.
        String normalized = normalizeForSandbox(code);

        // Phase 17 (post-hang analyses §1.3) — pre-parse pattern detection
        // for the two dominant LLM mis-translations: JavaScript member-access
        // (`.push`, `.length`, etc., 43% of static-parse fails across 18h of
        // logs) and object literals (`{key: value}`, 22%). The lexer's
        // "Unexpected char '.'" / "Unexpected token '{'" rejections don't
        // tell the LLM *what* failed; specific messages here cut the retry
        // count and steer the LLM toward `recordSample()` / fixed-size
        // arrays. Detection is line-based and only triggers on clear
        // member-access or object-literal shapes; numeric literals like
        // `1.5e-3` are unaffected.
        Result preScan = preScanForJavascriptIdioms(normalized);
        if (preScan instanceof Result.Rejected) return preScan;

        List<SandboxEngine.Stmt> program;
        try {
            List<SandboxEngine.Token> tokens = SandboxEngine.lex(normalized);
            program = new SandboxEngine.Parser(tokens).parseProgram();
        } catch (RuntimeException e) {
            // Lexer rejects unsupported chars (strings, arrays, member access).
            // Parser rejects unknown syntax (imports, class, while, function decl).
            return new Result.Rejected("parse failed: " + e.getMessage());
        }

        // Phase 8 Tier 2 — collect user-defined function names from a single
        // hoisting pass. Calls that reference these names are legal even though
        // the names are not in BUILTINS. Reject definitions that shadow a builtin
        // here so the call-walk doesn't have to know about that conflict.
        Set<String> userFunctions = new HashSet<>();
        for (SandboxEngine.Stmt s : program) {
            if (s instanceof SandboxEngine.Stmt.FuncDef fd) {
                if (SandboxEngine.BUILTINS.contains(fd.name())) {
                    return new Result.Rejected(
                            "user function '" + fd.name() + "' shadows a builtin");
                }
                if (!userFunctions.add(fd.name())) {
                    return new Result.Rejected(
                            "user function '" + fd.name() + "' redefined");
                }
            }
        }

        List<String> bad = new ArrayList<>();
        for (SandboxEngine.Stmt s : program) collectBadCalls(s, userFunctions, bad);
        if (!bad.isEmpty()) {
            return new Result.Rejected("non-whitelisted function(s): "
                    + String.join(", ", bad));
        }
        return new Result.Approved();
    }

    /**
     * Phase 10 — grammar reminder appended to rejection messages that reach the
     * LLM. The LLM was trained on JavaScript and keeps submitting code that
     * uses constructs (member access, strings, arrays-via-{@code .push},
     * object literals) which the sandbox subset deliberately rejects. With no
     * grammar visible in the rejection, the typical failure pattern is four
     * progressively-simpler retries that all hit different non-JS-subset
     * rules; this text fixes that by spelling out the subset on every
     * rejection.
     *
     * <p>The list of builtins is intentionally a representative selection
     * rather than the full computed set — the harness already maintains the
     * canonical list in {@link SandboxEngine#BUILTINS}.
     */
    public static String helpText() {
        return """

                Sandbox grammar (NOT JavaScript — a deliberately small safe subset):
                  Statements:  var X = expr;   X = expr;   return expr;
                               (comma-separated `var a = 1, b = 2;` also OK)
                               if (c) { ... } else { ... }   ← braces optional
                               for (init; cond; upd) { ... }   ← braces optional
                               while (cond) { ... }            ← braces optional
                               (single-statement form also accepted, e.g.
                               `if (cond) return x;` or
                               `while (n > 0) n = n - 1;`)
                               break;  continue;  ← loop control inside for/while
                               function f(args) { ... }
                  Operators:   + - * / % **   ==  !=  <  >  <=  >=   &&  ||  !   ?:
                               (=== and !== are accepted as aliases for == and !=)
                  Arrays:      var xs = [1, 2, 3];   xs[i] = v;   xs[i] += v;
                               len(xs);  xs.push(v) appends within a preallocated
                                          element cap (default 16384, operator-
                                          configurable; no resize/splice/pop).
                                          `xs.length` auto-rewrites to `len(xs)`,
                                          so the JS reflex is fine to emit.
                  Multi-value: `return {a: x, b: y};` and `return [x, y, z];`
                               are both auto-rewritten to a sequence of
                               `recordSample(value)` calls plus a scalar return
                               of the first value, so the harness's
                               n/mean/std/min/max aggregates surface every
                               returned value to the caller.
                  Stats:       recordSample(v)  accumulates n/mean/std/min/max
                                                across the run; the harness sees
                                                them alongside your return value.
                                                Use this to return multiple
                                                correlated statistics from one
                                                tool call.
                               signedDevPercent(actual, expected)
                                                returns (actual − expected)/|expected|
                                                × 100 with the sign preserved.
                                                Use this when comparing an
                                                observation to a theoretical
                                                baseline so the sign cannot be
                                                flipped in narrative — positive
                                                = above, negative = below.
                  NOT supported: "string literals", {object: literals},
                                 member access on arbitrary objects
                                 (Math.<builtin> is auto-rewritten to the
                                 flat form — Math.sqrt → sqrt — so this
                                 specific case is fine to emit),
                                 let / const, arrow functions (=>),
                                 try / catch / throw, classes, imports
                  Built-in functions: every java.lang.Math method is available
                                      as a flat identifier (pow, sqrt, exp, log,
                                      log10, log2, sin, cos, tan, atan2, abs,
                                      floor, ceil, min, max, hypot, etc.).
                                      Plus: isPrime, isHappy, isAbundant,
                                      isPerfect, collatzSteps, collatzTerminates,
                                      sigmaSum, gcd, mobius, sternBrocotDepthOf,
                                      isSquarefree, mertensPartialSum,
                                      integrate(expr,var,lo,hi),
                                      differentiate(expr,var,at), simplify(expr),
                                      recordSample(v), len(arr).
                  Working example:
                    var xs = [start, start+1, start+2, start+3];
                    var s = 0;
                    for (var i = 0; i < len(xs); i++) {
                      var v = collatzSteps(xs[i]);
                      recordSample(v);
                      s = s + v;
                    }
                    return s;
                """;
    }

    // ── Phase 19 Layer 1a/1b: pre-parse rewrites ──────────────────────────

    /**
     * {@code Math\.<name>} occurrences where {@code <name>} is in
     * {@link SandboxEngine#BUILTINS}. The capture group is the bare name
     * so the rewrite can replace the whole match with just {@code <name>}.
     * Word-boundary lookbehind avoids matching inside identifier names
     * like {@code myMath.sqrt}; word-boundary lookahead avoids partial
     * matches inside {@code Math.sqrtish}.
     */
    private static final java.util.regex.Pattern MATH_PREFIX =
            java.util.regex.Pattern.compile("(?<![A-Za-z_0-9])Math\\.([A-Za-z_]\\w*)\\b");

    /**
     * {@code <ident>.length} where {@code <ident>} is any simple
     * identifier other than {@code Math} (which doesn't have a length
     * property even in JS, so leave the existing member-access reject
     * intact for the diagnostic message). The capture group is the
     * receiver identifier so the rewrite produces {@code len(<ident>)}.
     */
    private static final java.util.regex.Pattern LENGTH_ACCESS =
            java.util.regex.Pattern.compile(
                    "(?<![A-Za-z_0-9])(?!Math\\b)([A-Za-z_]\\w*)\\.length\\b");

    /**
     * Strips the {@code Math.} prefix from any {@code Math.<name>} where
     * {@code <name>} is a sandbox builtin. The LLM's training corpus
     * emphasises the JavaScript {@code Math.*} static namespace; the
     * sandbox grammar already exposes every {@code java.lang.Math} double
     * method (plus {@code PI}, {@code E}) as a flat identifier, so the
     * rewrite is a no-semantics-change normalisation.
     *
     * <p>{@code Math.<non-builtin>} (e.g. {@code Math.random}) is left as
     * a literal so {@link #preScanForJavascriptIdioms} rejects it with
     * the existing member-access message — useful since the runtime
     * couldn't execute it anyway.
     *
     * <p>Visibility is package-private so {@link SandboxEngine} can apply
     * the same normalisation to its runtime entry point, and so tests
     * can pin the regex behaviour without standing up the full pipeline.
     */
    static String rewriteMathPrefix(String code) {
        if (code == null || code.indexOf("Math.") < 0) return code;
        java.util.regex.Matcher m = MATH_PREFIX.matcher(code);
        StringBuilder sb = new StringBuilder(code.length());
        while (m.find()) {
            String name = m.group(1);
            // Treat `PI` / `E` as builtins too — the sandbox runtime
            // resolves them via the same dispatch table.
            if (SandboxEngine.BUILTINS.contains(name)
                    || "PI".equals(name) || "E".equals(name)) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(name));
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Phase 19 Layer 1b — rewrites {@code <ident>.length} to the flat
     * builtin {@code len(<ident>)}. Same motivation as the Math.* rewrite:
     * the JS reflex is {@code arr.length}; the sandbox runtime exposes
     * {@code len(arr)} as a flat builtin returning the same value, so the
     * rewrite is semantics-preserving when {@code <ident>} resolves to an
     * array at runtime. For non-array receivers, {@code len()} throws a
     * clearer error than the lexer's {@code "Unexpected char '.'"}.
     *
     * <p>{@code Math.length} is deliberately excluded — Math has no length
     * even in JS, so leave the member-access reject intact for a useful
     * diagnostic.
     *
     * <p>Package-private for {@link SandboxEngine} runtime use and tests.
     */
    static String rewriteArrayLength(String code) {
        if (code == null || code.indexOf(".length") < 0) return code;
        java.util.regex.Matcher m = LENGTH_ACCESS.matcher(code);
        StringBuilder sb = new StringBuilder(code.length());
        while (m.find()) {
            String receiver = m.group(1);
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement("len(" + receiver + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Return-position object literal pattern. Multi-line aware (DOTALL).
     * The capture group is the body between {@code {} and {@code }}.
     * Restricted to a single flat object — nested braces in the body cause
     * the simple {@code [^{}]+} class to miss, and we let the rewrite
     * skip them (the existing reject path fires instead).
     */
    private static final java.util.regex.Pattern RETURN_OBJECT_LITERAL =
            java.util.regex.Pattern.compile(
                    "\\breturn\\s*\\{\\s*([^{}]+?)\\s*\\}\\s*;",
                    java.util.regex.Pattern.DOTALL);

    /**
     * Phase 19 Layer 1c — rewrites {@code return {k1: v1, k2: v2, …};} to
     * a sequence of {@code recordSample(v_i);} calls followed by
     * {@code return v_1;}. Targets the structured-multi-value return shape
     * (P-a328-678 RLC: {@code return {Rcrit:Rcrit, omega0:omega0, Qcrit:Qcrit};})
     * that today's grammar rejects as an object literal.
     *
     * <p>Semantics preserved for the LLM's purposes: each returned value
     * appears in the SandboxResult's recordSample aggregates (n/mean/std/
     * min/max), and the primary scalar is the first value. The LLM's
     * output prose can recover the multi-value information from the
     * tool-response summary.
     *
     * <p>Conservative scoping: only single-flat-object returns rewrite.
     * Nested braces in the body (sub-object literals, function bodies)
     * cause the regex to miss; those fall through to the existing reject
     * path with the Layer-2 hint message. No regression vs today.
     *
     * <p>Key/value splitting respects parenthesis depth so values with
     * sub-calls like {@code {a: pow(2, 3), b: sqrt(5)}} parse correctly
     * (the comma inside {@code pow(2, 3)} is not a separator).
     *
     * <p>Package-private for runtime use and tests.
     */
    static String rewriteReturnObjectLiteral(String code) {
        return rewriteReturnObjectLiteralWithKeys(code, null);
    }

    /**
     * Same as {@link #rewriteReturnObjectLiteral(String)} but additionally
     * appends the matched object literal's key names (in source order) into
     * {@code keySink}. When the rewrite fires on multiple return statements,
     * the first one wins — only one set of named keys can be surfaced per
     * run because the runtime only tracks the trailing recordSample window.
     */
    private static String rewriteReturnObjectLiteralWithKeys(
            String code, java.util.List<String> keySink) {
        if (code == null || code.indexOf("return") < 0 || code.indexOf("{") < 0) {
            return code;
        }
        java.util.regex.Matcher m = RETURN_OBJECT_LITERAL.matcher(code);
        StringBuilder sb = new StringBuilder(code.length());
        boolean keysCaptured = false;
        while (m.find()) {
            String body = m.group(1).trim();
            java.util.List<String> kvs = splitObjectLiteralKeysAndValues(body);
            if (kvs == null || kvs.isEmpty()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            int n = kvs.size() / 2;
            java.util.List<String> values = new ArrayList<>(n);
            java.util.List<String> keys = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                keys.add(kvs.get(2 * i));
                values.add(kvs.get(2 * i + 1));
            }
            if (keySink != null && !keysCaptured) {
                keySink.addAll(keys);
                keysCaptured = true;
            }
            StringBuilder replacement = new StringBuilder();
            for (String v : values) {
                replacement.append("recordSample(").append(v).append("); ");
            }
            replacement.append("return ").append(values.get(0)).append(";");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Return-position array literal pattern. Multi-line aware (DOTALL).
     * The capture group is the body between {@code [} and {@code ]}.
     * Restricted to a single flat array — nested brackets in the body
     * cause the {@code [^\[\]]+?} character class to miss, and we let
     * the rewrite skip them (the runtime's existing array-as-subexpression
     * reject fires instead).
     */
    private static final java.util.regex.Pattern RETURN_ARRAY_LITERAL =
            java.util.regex.Pattern.compile(
                    "\\breturn\\s*\\[\\s*([^\\[\\]]+?)\\s*\\]\\s*;",
                    java.util.regex.Pattern.DOTALL);

    /**
     * Phase 19 Layer 1c (array variant) — rewrites {@code return [a, b, c];}
     * to a sequence of {@code recordSample(v_i);} calls followed by
     * {@code return v_1;}. Same motivation as the object-literal rewrite:
     * the LLM emits {@code return [mean, std, n];}-style multi-value
     * returns that the runtime rejects with
     * "Array literal can only appear as the RHS of a `var` declaration".
     *
     * <p>Semantics preserved for the LLM's purposes: each returned value
     * appears in the SandboxResult's recordSample aggregates (n/mean/std/
     * min/max), and the primary scalar is the first value. Mirrors the
     * object-literal rewrite's contract.
     *
     * <p>Conservative scoping: only single-flat-array returns rewrite.
     * Nested brackets in the body fall through to the existing reject path.
     *
     * <p>Element splitting respects parenthesis depth so values with
     * sub-calls like {@code [pow(2, 3), sqrt(5)]} parse correctly.
     *
     * <p>Package-private for runtime use and tests.
     */
    static String rewriteReturnArrayLiteral(String code) {
        return rewriteReturnArrayLiteralWithKeys(code, null);
    }

    /**
     * Same as {@link #rewriteReturnArrayLiteral(String)} but additionally
     * appends synthetic positional key names ({@code "0"}, {@code "1"}, …)
     * into {@code keySink} so the runtime can pair the trailing
     * {@code recordSample} window with positional labels in the response
     * prose. First-match-wins on multiple returns (same constraint as
     * the object variant).
     */
    private static String rewriteReturnArrayLiteralWithKeys(
            String code, java.util.List<String> keySink) {
        if (code == null || code.indexOf("return") < 0 || code.indexOf("[") < 0) {
            return code;
        }
        java.util.regex.Matcher m = RETURN_ARRAY_LITERAL.matcher(code);
        StringBuilder sb = new StringBuilder(code.length());
        boolean keysCaptured = false;
        while (m.find()) {
            String body = m.group(1).trim();
            java.util.List<String> values = splitTopLevelCommas(body);
            if (values == null || values.isEmpty()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (keySink != null && !keysCaptured) {
                for (int i = 0; i < values.size(); i++) {
                    keySink.add(Integer.toString(i));
                }
                keysCaptured = true;
            }
            StringBuilder replacement = new StringBuilder();
            for (String v : values) {
                replacement.append("recordSample(").append(v).append("); ");
            }
            replacement.append("return ").append(values.get(0)).append(";");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Splits a comma-separated body into a list of expressions, respecting
     * parenthesis/bracket depth so commas inside function calls or index
     * expressions don't split values. Returns {@code null} on malformed
     * input (unbalanced parens, empty element) so the caller can fall
     * back to the no-rewrite path. Shared by the array-literal rewrite
     * (no colons) and the object-literal rewrite (after the colon strip).
     */
    private static java.util.List<String> splitTopLevelCommas(String body) {
        java.util.List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (--depth < 0) return null;
            }
            else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        if (depth != 0) return null;
        parts.add(body.substring(start));
        for (String p : parts) {
            if (p.trim().isEmpty()) return null;
        }
        java.util.List<String> trimmed = new ArrayList<>(parts.size());
        for (String p : parts) trimmed.add(p.trim());
        return trimmed;
    }

    /**
     * Splits an object-literal body ({@code "k1: v1, k2: v2"}) into a
     * list of value expressions, respecting parenthesis depth so commas
     * inside function calls don't split values. Returns {@code null} on
     * malformed input (unbalanced parens, missing colon) so the caller
     * can fall back to the no-rewrite path.
     */
    private static java.util.List<String> splitObjectLiteralValues(String body) {
        java.util.List<String> kvs = splitObjectLiteralKeysAndValues(body);
        if (kvs == null) return null;
        java.util.List<String> values = new ArrayList<>(kvs.size() / 2);
        for (int i = 1; i < kvs.size(); i += 2) values.add(kvs.get(i));
        return values;
    }

    /**
     * Splits an object-literal body ({@code "k1: v1, k2: v2"}) into a
     * flat interleaved [key0, value0, key1, value1, …] list, respecting
     * parenthesis depth. Returns {@code null} on malformed input so the
     * caller can fall back to the no-rewrite path.
     */
    private static java.util.List<String> splitObjectLiteralKeysAndValues(String body) {
        java.util.List<String> pairs = splitTopLevelCommas(body);
        if (pairs == null) return null;
        java.util.List<String> kvs = new ArrayList<>(pairs.size() * 2);
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon < 0) return null;
            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();
            if (key.isEmpty() || value.isEmpty()) return null;
            kvs.add(key);
            kvs.add(value);
        }
        return kvs;
    }

    /**
     * Applies all Phase 19 Layer-1 pre-parse rewrites in order. Centralises
     * the call so {@link #check(String)} and {@link SandboxEngine#run(String, int, int, int, double)}
     * stay in lockstep.
     *
     * <p>Order matters: Math.* first (so `Math.sqrt(arr.length)` becomes
     * `sqrt(arr.length)`), then `.length` (becomes `sqrt(len(arr))`), then
     * the two return-literal rewrites (so values like `Math.sqrt(x)` and
     * `arr.length` inside object / array literals are already normalised
     * by the time we copy them into the recordSample calls).
     */
    static String normalizeForSandbox(String code) {
        return normalizeWithMetadata(code).code();
    }

    /**
     * Same Layer-1 normalisation as {@link #normalizeForSandbox(String)},
     * but also captures the named-return metadata so the runtime can pair
     * the trailing recordSample window with key names in
     * {@link SandboxEngine.SandboxResult#namedValues()}.
     *
     * <p>The two rewrites that produce named output ({@link #rewriteReturnObjectLiteral}
     * and {@link #rewriteReturnArrayLiteral}) each have a sibling
     * {@code …WithKeys} method that pushes the keys into a sink list. We
     * call both, but only one will actually capture keys (the rewrite
     * patterns are mutually exclusive on a given return statement). If
     * both fire (multiple return statements with different shapes), the
     * first-matched rewrite's keys win — see the {@code keySink} field
     * doc on each helper.
     */
    public static Normalized normalizeWithMetadata(String code) {
        if (!relaxedGrammarEnabled) {
            return new Normalized(code, java.util.List.of());
        }
        String afterScalar = rewriteArrayLength(rewriteMathPrefix(code));
        java.util.List<String> keys = new ArrayList<>();
        String afterObj = rewriteReturnObjectLiteralWithKeys(afterScalar, keys);
        String afterArr = rewriteReturnArrayLiteralWithKeys(afterObj, keys);
        return new Normalized(afterArr, java.util.List.copyOf(keys));
    }

    // ── Phase 19 Layer 2: per-rejection rewrite hint ──────────────────────

    /**
     * Returns a single-line concrete rewrite suggestion for the most common
     * rejection patterns, or {@code ""} when no specific rewrite applies.
     *
     * <p>The generic {@link #helpText()} spells out the whole sandbox grammar
     * for the LLM; that's useful but verbose. When a rejection matches a
     * known pattern (member access on a JS-style method, object literal,
     * comma-separated declaration, etc.), the LLM benefits from seeing the
     * <em>specific</em> fix one line above the grammar dump. Empirically the
     * specific rewrite cuts retry counts more than the grammar dump alone.
     *
     * <p>Match order is most-specific-first. The {@code reason} argument is
     * the verdict string from {@link Result.Rejected#reason()} — typically
     * something like {@code "REJECTED (static): member access not
     * supported: '.push' — …"}; the prefix stripping is handled here so
     * callers can pass the full verdict.
     */
    public static String rewriteHintFor(String reason) {
        if (reason == null || reason.isBlank()) return "";
        String r = reason;
        // Strip the "REJECTED (static): " prefix that MathToolSuite prepends
        // before forwarding to the LLM, so the match below can pin on the
        // bare static-rule message.
        int idx = r.indexOf("static):");
        if (idx >= 0) r = r.substring(idx + "static):".length()).trim();

        // Member-access cases — extract the specific method to tailor the hint.
        if (r.startsWith("member access not supported: '.")) {
            int q1 = r.indexOf('\'');
            int q2 = r.indexOf('\'', q1 + 1);
            String method = (q1 >= 0 && q2 > q1) ? r.substring(q1 + 2, q2) : "";
            return switch (method) {
                case "push", "pop", "shift", "unshift", "splice" ->
                        "Quick fix: arrays are fixed-size. Declare `var xs = [0, 0, 0, …];` "
                        + "with the max length you need, track a count `var n = 0;`, "
                        + "and assign `xs[n] = v; n = n + 1;` instead of `.push`.";
                case "length" ->
                        "Quick fix: replace `arr.length` with `len(arr)`.";
                case "map", "filter", "reduce", "forEach" ->
                        "Quick fix: replace `arr." + method + "(f)` with an explicit "
                        + "`for (var i = 0; i < len(arr); i++) { … }` loop.";
                case "sqrt", "pow", "exp", "log", "sin", "cos", "tan", "abs",
                        "floor", "ceil", "min", "max", "PI", "E" ->
                        "Quick fix: drop the `Math.` prefix — call `" + method + "(…)` "
                        + "directly (every java.lang.Math method is a flat builtin here).";
                case "toFixed", "toString", "toPrecision" ->
                        "Quick fix: return the raw number — formatting is the caller's job, "
                        + "not the sandbox's.";
                default ->
                        "Quick fix: the sandbox has no objects or methods. If you need "
                        + "to extract a property, compute the value directly; if you "
                        + "need to call a function, look up the builtin name in the "
                        + "grammar reference below.";
            };
        }

        if (r.startsWith("object literals")) {
            return "Quick fix: replace `return {a: x, b: y}` with `recordSample(x); "
                    + "recordSample(y); return x;` — the harness returns n/mean/std/min/max "
                    + "alongside your scalar, covering the multi-value case.";
        }

        if (r.startsWith("parse failed:")) {
            String pf = r.substring("parse failed:".length()).trim();
            if (pf.contains("got ','")) {
                return "Quick fix: declare one variable per statement. "
                        + "`var a = 1, b = 2;` is not allowed; use `var a = 1; var b = 2;`.";
            }
            if (pf.contains("got 'break'") || pf.contains("got 'continue'")) {
                return "Quick fix: `break` / `continue` must appear inside a `{ }` block. "
                        + "Wrap the conditional: `if (cond) { break; }` rather than "
                        + "`if (cond) break;`.";
            }
            if (pf.contains("Unexpected char '\"'") || pf.contains("Unexpected char '\\''")) {
                return "Quick fix: the sandbox has no strings. Drop the quoted literal — "
                        + "if it was a comment label, use `//` line comments instead.";
            }
            if (pf.contains("Unexpected char '['")) {
                return "Quick fix: array literals must appear on the right-hand side of a "
                        + "declaration (`var xs = [1, 2, 3];`), not inside an expression "
                        + "or function call.";
            }
            if (pf.contains("Unexpected token 'let'") || pf.contains("got 'let'")
                    || pf.contains("got 'const'")) {
                return "Quick fix: use `var` for all declarations. `let` and `const` "
                        + "are not in the sandbox grammar.";
            }
        }

        if (r.startsWith("non-whitelisted function(s):")) {
            return "Quick fix: every called name must be a builtin (see grammar below) "
                    + "or a `function` you defined earlier in the same code block.";
        }

        if (r.startsWith("user function") && r.contains("shadows a builtin")) {
            return "Quick fix: rename your function — the name collides with a sandbox "
                    + "builtin. Add a domain prefix (e.g. `myAbs` instead of `abs`).";
        }

        return "";
    }

    // ── Phase 17: pre-parse pattern detection ─────────────────────────────

    /**
     * Member-access pattern: identifier or close-bracket, followed by `.`,
     * followed by an identifier (the would-be method/property name) — and
     * <em>not</em> a digit (which would be a numeric literal continuation).
     *
     * <p>Anchored examples that match: {@code xs.push(1)}, {@code arr.length},
     * {@code Math.pow(2, 3)}, {@code result.toFixed(2)}, {@code }gaps[i].push(v)}.
     * Anchored examples that do <em>not</em> match: {@code 1.5}, {@code .5e-3}
     * (no identifier before the dot), {@code 2e3}, {@code N.0} (digit after dot).
     */
    private static final java.util.regex.Pattern MEMBER_ACCESS =
            java.util.regex.Pattern.compile("(?:[A-Za-z_]\\w*|\\])\\.([A-Za-z_]\\w*)");

    /**
     * Object-literal pattern: open-brace, optional whitespace, identifier,
     * optional whitespace, colon. Excludes the legitimate cases:
     *   - block opener after a control-flow keyword: `if (cond) {` — no
     *     `identifier:` follows the brace
     *   - ternary `cond ? a : b` — colon is not preceded by `{ ident`
     */
    private static final java.util.regex.Pattern OBJECT_LITERAL =
            java.util.regex.Pattern.compile("\\{\\s*[A-Za-z_]\\w*\\s*:");

    /**
     * Phase 17 (post-hang analyses §1.3) — detect JavaScript idioms the
     * LLM keeps emitting (member-access {@code .push}/{@code .length}/etc.;
     * object literals {@code {key: value}}) and reject with a
     * specific, instructive message <em>before</em> the lexer produces its
     * less-helpful "Unexpected char" output.
     *
     * <p>Visibility is package-private so unit tests can pin the regex
     * decisions without standing up the full {@link #check(String)}
     * pipeline.
     */
    static Result preScanForJavascriptIdioms(String code) {
        if (code == null) return new Result.Approved();
        // Member-access first (the larger cluster: 43% of static-parse fails).
        // Paper-review 2026-05-22 — walk all matches and skip the now-supported
        // `<ident>.push(<expr>)` form (added in the same pass; see SandboxEngine
        // Stmt.ArrayPush + SandboxArr). Any other `.method`, AND `<bracket>.push`
        // (push on an array element — not supported), still rejects.
        java.util.regex.Matcher m = MEMBER_ACCESS.matcher(code);
        while (m.find()) {
            String method = m.group(1);
            String full   = m.group();
            // Only `<ident>.push(...)` is supported. `gs[0].push(...)` matches
            // the regex via the `\]` alternative and must still reject.
            if ("push".equals(method) && !full.startsWith("]")) {
                continue;
            }
            return new Result.Rejected(
                    "member access not supported: '." + method + "' — "
                            + "the safe grammar has no objects or methods. "
                            + "For arrays: declare a fixed size `var xs = [0, 0, …]` "
                            + "and index into it (`xs[i] = v`); use `len(xs)` for length; "
                            + "use `xs.push(v)` to grow up to " + SandboxEngine.MAX_ARRAY_LEN
                            + " elements. "
                            + "For Math: call `pow`, `sqrt`, `sin`, etc. as flat "
                            + "identifiers (no `Math.` prefix). To collect statistics "
                            + "across iterations, call `recordSample(value)` at each "
                            + "step — the harness returns n/mean/std/min/max alongside "
                            + "your return value.");
        }
        // Object literal second (22% of static-parse fails).
        if (OBJECT_LITERAL.matcher(code).find()) {
            return new Result.Rejected(
                    "object literals `{key: value}` not supported — "
                            + "the safe grammar has no objects. To return multiple "
                            + "correlated statistics, call `recordSample(value)` at "
                            + "each computation step; the harness exposes "
                            + "n/mean/std/min/max for free. Your return statement "
                            + "should be a single representative scalar.");
        }
        return new Result.Approved();
    }

    // ── AST walk ──────────────────────────────────────────────────────────────

    private static void collectBadCalls(SandboxEngine.Stmt s,
                                        Set<String> userFunctions,
                                        List<String> bad) {
        switch (s) {
            case SandboxEngine.Stmt.Decl d   -> walkExpr(d.init(), userFunctions, bad);
            case SandboxEngine.Stmt.ExprS e  -> walkExpr(e.expr(), userFunctions, bad);
            case SandboxEngine.Stmt.Return r -> walkExpr(r.val(), userFunctions, bad);
            case SandboxEngine.Stmt.Break b  -> { /* leaf */ }
            case SandboxEngine.Stmt.Continue c -> { /* leaf */ }
            case SandboxEngine.Stmt.Compound cp -> {
                for (SandboxEngine.Stmt b : cp.body()) collectBadCalls(b, userFunctions, bad);
            }
            case SandboxEngine.Stmt.If f -> {
                walkExpr(f.cond(), userFunctions, bad);
                for (SandboxEngine.Stmt b : f.then()) collectBadCalls(b, userFunctions, bad);
                for (SandboxEngine.Stmt b : f.els())  collectBadCalls(b, userFunctions, bad);
            }
            case SandboxEngine.Stmt.For f -> {
                walkExpr(f.init(), userFunctions, bad);
                walkExpr(f.cond(), userFunctions, bad);
                walkExpr(f.upd(),  userFunctions, bad);
                for (SandboxEngine.Stmt b : f.body()) collectBadCalls(b, userFunctions, bad);
            }
            case SandboxEngine.Stmt.While w -> {
                walkExpr(w.cond(), userFunctions, bad);
                for (SandboxEngine.Stmt b : w.body()) collectBadCalls(b, userFunctions, bad);
            }
            case SandboxEngine.Stmt.FuncDef fd -> {
                for (SandboxEngine.Stmt b : fd.body()) collectBadCalls(b, userFunctions, bad);
            }
            case SandboxEngine.Stmt.ArrayPush ap -> walkExpr(ap.value(), userFunctions, bad);
        }
    }

    private static void walkExpr(SandboxEngine.Expr e,
                                 Set<String> userFunctions,
                                 List<String> bad) {
        switch (e) {
            case SandboxEngine.Expr.Num n      -> { /* leaf */ }
            case SandboxEngine.Expr.Bool b     -> { /* leaf */ }
            case SandboxEngine.Expr.Var v      -> { /* leaf */ }
            case SandboxEngine.Expr.PostInc p  -> { /* leaf */ }
            case SandboxEngine.Expr.PostDec p  -> { /* leaf */ }
            case SandboxEngine.Expr.UnOp u     -> walkExpr(u.e(), userFunctions, bad);
            case SandboxEngine.Expr.BinOp b    -> { walkExpr(b.l(), userFunctions, bad); walkExpr(b.r(), userFunctions, bad); }
            case SandboxEngine.Expr.Ternary t  -> { walkExpr(t.cond(), userFunctions, bad); walkExpr(t.then(), userFunctions, bad); walkExpr(t.els(), userFunctions, bad); }
            case SandboxEngine.Expr.Assign a   -> walkExpr(a.val(), userFunctions, bad);
            case SandboxEngine.Expr.Call c -> {
                if (!SandboxEngine.BUILTINS.contains(c.name())
                        && !userFunctions.contains(c.name())) {
                    bad.add(c.name());
                }
                for (SandboxEngine.Expr arg : c.args()) walkExpr(arg, userFunctions, bad);
            }
            case SandboxEngine.Expr.ArrayLit a -> {
                for (SandboxEngine.Expr el : a.elems()) walkExpr(el, userFunctions, bad);
            }
            case SandboxEngine.Expr.Index ix -> walkExpr(ix.idx(), userFunctions, bad);
            case SandboxEngine.Expr.IndexAssign ix -> {
                walkExpr(ix.idx(), userFunctions, bad);
                walkExpr(ix.val(), userFunctions, bad);
            }
        }
    }
}
