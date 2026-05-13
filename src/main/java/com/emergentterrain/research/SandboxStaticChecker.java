package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;

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

        List<String> bad = new ArrayList<>();
        for (SandboxEngine.Stmt s : program) collectBadCalls(s, bad);
        if (!bad.isEmpty()) {
            return new Result.Rejected("non-whitelisted function(s): "
                    + String.join(", ", bad));
        }
        return new Result.Approved();
    }

    // ── AST walk ──────────────────────────────────────────────────────────────

    private static void collectBadCalls(SandboxEngine.Stmt s, List<String> bad) {
        switch (s) {
            case SandboxEngine.Stmt.Decl d   -> walkExpr(d.init(), bad);
            case SandboxEngine.Stmt.ExprS e  -> walkExpr(e.expr(), bad);
            case SandboxEngine.Stmt.Return r -> walkExpr(r.val(), bad);
            case SandboxEngine.Stmt.If f -> {
                walkExpr(f.cond(), bad);
                for (SandboxEngine.Stmt b : f.then()) collectBadCalls(b, bad);
                for (SandboxEngine.Stmt b : f.els())  collectBadCalls(b, bad);
            }
            case SandboxEngine.Stmt.For f -> {
                walkExpr(f.init(), bad);
                walkExpr(f.cond(), bad);
                walkExpr(f.upd(),  bad);
                for (SandboxEngine.Stmt b : f.body()) collectBadCalls(b, bad);
            }
        }
    }

    private static void walkExpr(SandboxEngine.Expr e, List<String> bad) {
        switch (e) {
            case SandboxEngine.Expr.Num n      -> { /* leaf */ }
            case SandboxEngine.Expr.Bool b     -> { /* leaf */ }
            case SandboxEngine.Expr.Var v      -> { /* leaf */ }
            case SandboxEngine.Expr.PostInc p  -> { /* leaf */ }
            case SandboxEngine.Expr.PostDec p  -> { /* leaf */ }
            case SandboxEngine.Expr.UnOp u     -> walkExpr(u.e(), bad);
            case SandboxEngine.Expr.BinOp b    -> { walkExpr(b.l(), bad); walkExpr(b.r(), bad); }
            case SandboxEngine.Expr.Ternary t  -> { walkExpr(t.cond(), bad); walkExpr(t.then(), bad); walkExpr(t.els(), bad); }
            case SandboxEngine.Expr.Assign a   -> walkExpr(a.val(), bad);
            case SandboxEngine.Expr.Call c -> {
                if (!SandboxEngine.BUILTINS.contains(c.name())) bad.add(c.name());
                for (SandboxEngine.Expr arg : c.args()) walkExpr(arg, bad);
            }
        }
    }
}
