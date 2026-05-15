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

    public sealed interface Result {
        record Approved() implements Result {}
        record Rejected(String reason) implements Result {}
    }

    public static Result check(String code) {
        if (code == null || code.isBlank()) {
            return new Result.Rejected("empty code");
        }

        List<SandboxEngine.Stmt> program;
        try {
            List<SandboxEngine.Token> tokens = SandboxEngine.lex(code);
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
                               if (c) { ... } else { ... }
                               for (init; cond; upd) { ... }   while (cond) { ... }
                               function f(args) { ... }
                  Operators:   + - * / % **   ==  !=  <  >  <=  >=   &&  ||  !   ?:
                               (=== and !== are accepted as aliases for == and !=)
                  Arrays:      var xs = [1, 2, 3];   xs[i] = v;   xs[i] += v;
                               len(xs)  — max 1024 elements per array, no resize,
                                          no .length property, no push/pop/splice
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
                                 member access ( Math.pow → just call pow ),
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

    // ── AST walk ──────────────────────────────────────────────────────────────

    private static void collectBadCalls(SandboxEngine.Stmt s,
                                        Set<String> userFunctions,
                                        List<String> bad) {
        switch (s) {
            case SandboxEngine.Stmt.Decl d   -> walkExpr(d.init(), userFunctions, bad);
            case SandboxEngine.Stmt.ExprS e  -> walkExpr(e.expr(), userFunctions, bad);
            case SandboxEngine.Stmt.Return r -> walkExpr(r.val(), userFunctions, bad);
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
