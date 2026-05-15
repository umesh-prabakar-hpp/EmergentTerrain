package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 8 Tier 4 — deterministic safety check for the {@code run_gpu_eval}
 * formula. Parallel to {@link SandboxStaticChecker} and
 * {@link SymbolicStaticChecker}, but for a STRICT SUBSET of
 * {@link SandboxEngine}'s grammar: pure scalar expressions over the bound
 * variable {@code n} (plus the constants {@code pi} and {@code e}), no
 * statements, no control flow, no user-defined functions.
 *
 * <p>The Rust side (see {@code eval_expr::validate} in
 * EmergentTerrainGPUEngine/src/eval_expr.rs) enforces the same set as the
 * source of truth. Running this check Java-side first means:
 * <ul>
 *   <li>Failure feedback lands in the LLM's hands without paying a gRPC
 *       round-trip.</li>
 *   <li>Rejections show up in the per-paper Call Path next to the other
 *       {@code *_reject} counters.</li>
 *   <li>Mismatch between Java and Rust allow-lists is caught by drift tests
 *       rather than by a confusing GPU response.</li>
 * </ul>
 *
 * <p>If you add a function to one side, add it to the other. The
 * authoritative names live in {@link #ALLOWED_FUNCTIONS} (Java) and
 * {@code ALLOWED_FNS} (Rust); deliberate symmetry, not coincidence.
 */
public final class GpuEvalStaticChecker {

    private GpuEvalStaticChecker() {}

    public sealed interface Result {
        record Approved() implements Result {}
        record Rejected(String reason) implements Result {}
    }

    /** Identifiers the LLM may reference as bare names inside the formula. */
    public static final Set<String> ALLOWED_VARS = Set.of("n", "pi", "e");

    /**
     * Function-name → arity. Names lower-case; the Rust side's allow list
     * mirrors this map exactly. Number-theoretic helpers (isPrime, mobius,
     * etc.) are deliberately ABSENT — they don't translate cleanly to f32
     * GPU code and the sandbox is the right place for those.
     */
    public static final Map<String, Integer> ALLOWED_FUNCTIONS = Map.ofEntries(
            Map.entry("sqrt",  1), Map.entry("cbrt",  1),
            Map.entry("log",   1), Map.entry("log2",  1), Map.entry("log10", 1),
            Map.entry("exp",   1),
            Map.entry("pow",   2), Map.entry("hypot", 2),
            Map.entry("sin",   1), Map.entry("cos",   1), Map.entry("tan",   1),
            Map.entry("asin",  1), Map.entry("acos",  1),
            Map.entry("atan",  1), Map.entry("atan2", 2),
            Map.entry("sinh",  1), Map.entry("cosh",  1), Map.entry("tanh",  1),
            Map.entry("abs",   1), Map.entry("sign",  1),
            Map.entry("floor", 1), Map.entry("ceil",  1), Map.entry("round", 1),
            Map.entry("min",   2), Map.entry("max",   2)
    );

    public static final int MAX_FORMULA_LEN = 1024;
    /** Matches the Rust side's MAX_RANGE for symmetric error semantics. */
    public static final long MAX_RANGE = 10_000_000L;

    public static Result check(String formula) {
        if (formula == null || formula.isBlank()) {
            return new Result.Rejected("empty formula");
        }
        if (formula.length() > MAX_FORMULA_LEN) {
            return new Result.Rejected("formula too long ("
                    + formula.length() + " > " + MAX_FORMULA_LEN + ")");
        }
        // Reject statement-level syntax up-front with a clearer message than
        // "Unexpected token ';'". The grammar permits expressions only.
        for (String banned : new String[] { "var ", "for ", "if ", "if(", "return ",
                                            "function ", "function(" }) {
            if (formula.contains(banned)) {
                return new Result.Rejected(
                        "statement syntax not allowed; this tool accepts a single "
                                + "expression in n only (saw '" + banned.trim() + "')");
            }
        }

        // Re-use SandboxEngine's lexer + expression parser. Statement
        // productions are not invoked, so `var`/`for`/`if`/`return`/`function`
        // never get parsed even if smuggled past the substring check.
        // We must also verify the expression consumed every token — otherwise
        // something like "n = 5" parses as just "n" with "= 5" leftover, and
        // the assignment goes silently accepted.
        SandboxEngine.Expr expr;
        try {
            List<SandboxEngine.Token> tokens = SandboxEngine.lex(formula);
            SandboxEngine.Parser parser = new SandboxEngine.Parser(tokens);
            expr = parser.expr();
            // Final token is always TT.EOF (lexer appends it) — anything else
            // remaining is leftover input we deliberately did not allow into
            // the grammar (assignment, semicolons, etc.).
            if (parser.pos < tokens.size() - 1) {
                SandboxEngine.Token next = tokens.get(parser.pos);
                return new Result.Rejected(
                        "unexpected token '" + next.v() + "' at line " + next.line()
                                + " — this tool accepts a single expression in n only");
            }
        } catch (RuntimeException e) {
            return new Result.Rejected("parse failed: " + e.getMessage());
        }

        List<String> bad = new ArrayList<>();
        walk(expr, bad);
        if (!bad.isEmpty()) {
            return new Result.Rejected(
                    "non-whitelisted name(s): "
                            + String.join(", ", new LinkedHashSet<>(bad)));
        }
        return new Result.Approved();
    }

    private static void walk(SandboxEngine.Expr e, List<String> bad) {
        switch (e) {
            case SandboxEngine.Expr.Num n      -> { /* leaf */ }
            case SandboxEngine.Expr.Bool b     -> bad.add("(boolean literal)");
            case SandboxEngine.Expr.Var v -> {
                if (!ALLOWED_VARS.contains(v.name())) bad.add(v.name());
            }
            case SandboxEngine.Expr.PostInc p  -> bad.add("(increment)");
            case SandboxEngine.Expr.PostDec p  -> bad.add("(decrement)");
            case SandboxEngine.Expr.Assign  a  -> bad.add("(assignment to " + a.name() + ")");
            case SandboxEngine.Expr.UnOp    u  -> walk(u.e(), bad);
            case SandboxEngine.Expr.BinOp   b  -> { walk(b.l(), bad); walk(b.r(), bad); }
            case SandboxEngine.Expr.Ternary t  -> { walk(t.cond(), bad); walk(t.then(), bad); walk(t.els(), bad); }
            case SandboxEngine.Expr.Call    c  -> {
                Integer expectedArity = ALLOWED_FUNCTIONS.get(c.name());
                if (expectedArity == null) {
                    bad.add(c.name());
                } else if (c.args().size() != expectedArity) {
                    bad.add(c.name() + "(arity " + c.args().size()
                            + ", expected " + expectedArity + ")");
                }
                for (SandboxEngine.Expr a : c.args()) walk(a, bad);
            }
            // Phase 10 — arrays and indexing are sandbox-only features; the
            // GPU expression form is a single scalar expression in n. Reject
            // any array construct that survives the parse.
            case SandboxEngine.Expr.ArrayLit a    -> bad.add("(array literal)");
            case SandboxEngine.Expr.Index ix      -> bad.add("(array index " + ix.arrName() + ")");
            case SandboxEngine.Expr.IndexAssign ix -> bad.add("(array assignment " + ix.arrName() + ")");
        }
    }
}
