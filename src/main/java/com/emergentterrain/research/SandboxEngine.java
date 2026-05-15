package com.emergentterrain.research;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs LLM-generated mathematical pseudocode in a safe, side-effect-free sandbox.
 *
 * Supported syntax (JavaScript-like):
 *   var count = 0;
 *   for (var n = start; n < end; n++) { if (isPrime(n)) { count++; } }
 *   return count / (end - start);
 *
 * Built-ins: isPrime, isHappy, collatzTerminates, isAbundant, isPerfect,
 *            collatzSteps, sigmaSum, abs, sqrt, pow, floor, ceil, log, log2, min, max, gcd
 * Pre-bound inputs: start, end, param1, param2.
 * Returns the value of the first {@code return} statement encountered, or 0.0.
 *
 * Safety limits: 5 000 total loop iterations, 50 000 total statements executed.
 * No I/O, no imports, no object creation — the interpreter only understands arithmetic.
 */
public class SandboxEngine {

    static final int MAX_ITERATIONS  = 5_000;
    static final int MAX_STMTS       = 50_000;
    /**
     * Phase 10 — array length cap. Fixed at allocation time; arrays have no
     * resize, push, or splice. The cap bounds memory per run (≤ 8 kB per
     * array, ≤ MAX_STMTS allocations of them) so the existing time-based
     * limits remain the dominant safety surface.
     */
    static final int MAX_ARRAY_LEN   = 1_024;
    /**
     * Phase 8 Tier 2 — user-defined function recursion depth. 64 frames covers
     * factorial(64), Newton-Raphson up to ~64 iters, mutual-recursion
     * isEven/isOdd, and continued-fraction expansions of typical research-paper
     * topics without giving runaway-recursion bugs room to consume the whole
     * MAX_STMTS budget before tripping.
     */
    static final int MAX_CALL_DEPTH  = 64;

    /**
     * Phase 8 Tier 1 — domain-specific (non-Math) builtins. Integer-typed helpers,
     * number-theoretic predicates, and aliases (log2) that {@code java.lang.Math}
     * does not provide directly. The full {@link #BUILTINS} set is the union of
     * this list and every double-signature method on {@code Math.class}
     * (see {@link #MATH_METHODS}).
     */
    static final Set<String> SPECIAL_BUILTINS = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect",
            "collatzSteps", "sigmaSum", "gcd", "log2",
            // Phase 8 Tier 1 — number-theory helpers already implemented in
            // MathPhysicsEngine but previously unreachable from the sandbox.
            // Backs the F3.4 MATHEMATICS topics (Mertens, Stern-Brocot, Squarefree, Mobius).
            "mobius", "sternBrocotDepthOf", "isSquarefree", "mertensPartialSum",
            // Phase 10 — recordSample(v) accumulates v into the run's
            // count/sum/sumSq/min/max so a single sandbox call can return
            // structured stats without strings, arrays, or object literals.
            // The function returns v unchanged so it composes inside expressions:
            //   sum = sum + recordSample(collatzSteps(n));
            "recordSample",
            // Phase 10 — `len(arr)` returns the fixed length of a sandbox
            // array. Short-circuited in evalCall so it can read the array
            // binding directly rather than receiving an unwrapped double.
            "len",
            // Phase 10 — `signedDevPercent(actual, expected)` returns the
            // SIGNED percentage deviation (actual − expected)/|expected| × 100.
            // P-a328-363 narrated a 38% twin-prime EXCESS as a "30-40% below
            // HL" deficit because the LLM did the comparison narratively after
            // seeing the two raw numbers. The deterministic helper lets the
            // LLM avoid the comparison and just read back a signed number.
            "signedDevPercent");

    /**
     * Phase 8 Tier 1 — every {@code public static double *(double[, double[, double]])}
     * method on {@code java.lang.Math}. Discovered reflectively at class-load time
     * so future JDK additions are picked up without code changes. Replaces the
     * hardcoded Phase 5 A3 trig+exp list and the per-name switch in {@code evalCall}.
     *
     * <p>Excludes {@code random()} (stateful, breaks sandbox determinism) and
     * any method with non-{@code double} parameters (int/long overloads of min/max,
     * scalb, floorDiv, etc. — these would silently widen and confuse callers).
     */
    static final Map<String, Method> MATH_METHODS = discoverMathMethods();

    /**
     * Phase 8 Pattern C — bridge builtins that delegate to {@link SymbolicEngine}.
     * The sandbox parses these calls into normal {@code Expr.Call} nodes; the
     * interpreter intercepts them before the regular {@code Math.*} / special
     * dispatch and walks the (unevaluated) argument AST into a Symja expression
     * string. Symja's evaluation produces the numeric answer that the sandbox
     * call returns.
     *
     * <p>Signatures:
     * <ul>
     *   <li>{@code integrate(expr, var, lo, hi)} — definite integral; numeric.</li>
     *   <li>{@code differentiate(expr, var, at)} — d/dvar[expr] evaluated at point.</li>
     *   <li>{@code simplify(expr)} — must reduce to a numeric; else error.</li>
     * </ul>
     */
    static final Set<String> BRIDGE_BUILTINS = Set.of(
            "integrate", "differentiate", "simplify");

    static final Set<String> BUILTINS = buildBuiltinsUnion();

    private static Map<String, Method> discoverMathMethods() {
        Map<String, Method> out = new HashMap<>();
        for (Method m : Math.class.getDeclaredMethods()) {
            int mods = m.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods)) continue;
            if (m.getReturnType() != double.class) continue;
            if ("random".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 0) continue;
            boolean allDouble = true;
            for (Class<?> pt : pts) if (pt != double.class) { allDouble = false; break; }
            if (!allDouble) continue;
            // If multiple double signatures exist for the same name (none do in JDK 21,
            // but be defensive), prefer the higher-arity one so 3-arg fma works.
            Method existing = out.get(m.getName());
            if (existing == null || existing.getParameterCount() < m.getParameterCount()) {
                out.put(m.getName(), m);
            }
        }
        return Map.copyOf(out);
    }

    private static Set<String> buildBuiltinsUnion() {
        Set<String> all = new HashSet<>(SPECIAL_BUILTINS);
        all.addAll(MATH_METHODS.keySet());
        all.addAll(BRIDGE_BUILTINS);
        return Set.copyOf(all);
    }

    /**
     * Phase 10 — structured sandbox result.
     *
     * <p>{@link #value} is the primary return (first {@code return}-statement,
     * or 0.0 if the program falls through). {@link #summary} is the same human
     * readable status string callers used before this commit.
     *
     * <p>The {@code recorded*} fields aggregate every call to the
     * {@code recordSample(v)} builtin made during the run. They let the LLM
     * return multiple correlated statistics from a single sandbox call without
     * needing strings or arrays — a workaround for the structured-result need
     * that drove papers like P-a328-361 to do four parse-failing
     * {@code return {mean, std, max, count}} attempts before recovering.
     * {@link #recordedN} is 0 when nothing was recorded; in that case the
     * mean/std/min/max are NaN.
     */
    public record SandboxResult(
            double value,
            String summary,
            int    recordedN,
            double recordedMean,
            double recordedStd,
            double recordedMin,
            double recordedMax) {
        /** Two-arg form for callers that don't use recordSample. */
        public SandboxResult(double value, String summary) {
            this(value, summary, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static SandboxResult run(String code, int start, int end, int param1, double param2) {
        Interpreter interp = new Interpreter(start, end, param1, param2);
        double result;
        try {
            List<Token> tokens  = lex(code);
            List<Stmt>  program = new Parser(tokens).parseProgram();
            result = interp.exec(program);
        } catch (ReturnSignal r) {
            result = r.value;
        }
        return interp.buildResult(result);
    }

    /** Evaluate a pure expression f(n) over [start, end) and return mean/std/min/max. */
    public static SandboxResult evalExpr(String expression, int start, int end) {
        try {
            List<Token> tokens = lex(expression);
            Expr expr = new Parser(tokens).expr();
            double sum = 0, sum2 = 0, mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            int n = end - start;
            if (n <= 0) return new SandboxResult(0, "empty range");
            Interpreter interp = new Interpreter(start, end, 0, 0.0);
            for (int i = start; i < end; i++) {
                interp.env.put("n", (double) i);
                double v = interp.eval(expr);
                sum  += v; sum2 += v * v;
                if (v < mn) mn = v; if (v > mx) mx = v;
            }
            double mean = sum / n;
            double std  = n > 1 ? Math.sqrt(Math.max(0, sum2 / n - mean * mean)) : 0;
            return new SandboxResult(mean,
                    "mean=%.4g  std=%.4g  min=%.4g  max=%.4g  n=%d"
                            .formatted(mean, std, mn, mx, n));
        } catch (Exception e) {
            throw new RuntimeException("Expression evaluation failed: " + e.getMessage(), e);
        }
    }

    // ── Tokens ────────────────────────────────────────────────────────────────

    enum TT {
        NUM, IDENT,
        PLUS, MINUS, STAR, SLASH, PCT, STARSTAR,
        PLUSPLUS, MINUSMINUS,
        PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ,
        LT, GT, LE, GE, EQ2, NEQ,
        AND2, OR2, BANG,
        EQ, SEMI, COMMA, LPAR, RPAR, LBRACE, RBRACE, LBRACK, RBRACK, QMARK, COLON,
        IF, ELSE, FOR, WHILE, VAR, RET, TRUE, FALSE,
        FUNCTION,  // Phase 8 Tier 2 — user-defined function declarations
        EOF
    }

    record Token(TT t, String v, int line) {}

    // ── Lexer ─────────────────────────────────────────────────────────────────

    static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0, line = 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                continue;
            }
            // Number
            if (Character.isDigit(c) || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                int s = i;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                // Scientific notation: [eE][+-]?digits. Only consume the e/E
                // when there's a digit (possibly after +/-) right after; otherwise
                // leave the e for the identifier scanner (e.g. `2*e` is two tokens).
                if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                    int j = i + 1;
                    if (j < src.length() && (src.charAt(j) == '+' || src.charAt(j) == '-')) j++;
                    if (j < src.length() && Character.isDigit(src.charAt(j))) {
                        i = j;
                        while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
                    }
                }
                out.add(new Token(TT.NUM, src.substring(s, i), line));
                continue;
            }
            // Identifier / keyword
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                String w = src.substring(s, i);
                TT t = switch (w) {
                    case "if"       -> TT.IF;       case "else"   -> TT.ELSE;
                    case "for"      -> TT.FOR;      case "while"  -> TT.WHILE;
                    case "var"      -> TT.VAR;      case "return" -> TT.RET;
                    case "true"     -> TT.TRUE;     case "false"  -> TT.FALSE;
                    case "function" -> TT.FUNCTION;
                    default -> TT.IDENT;
                };
                out.add(new Token(t, w, line));
                continue;
            }
            // Three-char operators: === and !==, aliased to == and !=.
            // The sandbox is single-typed (every value is a double), so strict
            // and loose equality have identical semantics; accepting both lets
            // LLMs trained on JS write either form without parse failure.
            char n  = (i + 1 < src.length()) ? src.charAt(i + 1) : 0;
            char nn = (i + 2 < src.length()) ? src.charAt(i + 2) : 0;
            if (c == '=' && n == '=' && nn == '=') {
                out.add(new Token(TT.EQ2, "===", line)); i += 3; continue;
            }
            if (c == '!' && n == '=' && nn == '=') {
                out.add(new Token(TT.NEQ, "!==", line)); i += 3; continue;
            }
            // Two-char operators
            Token two = switch (c) {
                case '*' -> n == '*' ? new Token(TT.STARSTAR,   "**", line)
                          : n == '=' ? new Token(TT.STAR_EQ,    "*=", line) : null;
                case '+' -> n == '+' ? new Token(TT.PLUSPLUS,   "++", line)
                          : n == '=' ? new Token(TT.PLUS_EQ,    "+=", line) : null;
                case '-' -> n == '-' ? new Token(TT.MINUSMINUS, "--", line)
                          : n == '=' ? new Token(TT.MINUS_EQ,   "-=", line) : null;
                case '/' -> n == '=' ? new Token(TT.SLASH_EQ,   "/=", line) : null;
                case '<' -> n == '=' ? new Token(TT.LE,         "<=", line) : null;
                case '>' -> n == '=' ? new Token(TT.GE,         ">=", line) : null;
                case '=' -> n == '=' ? new Token(TT.EQ2,        "==", line) : null;
                case '!' -> n == '=' ? new Token(TT.NEQ,        "!=", line) : null;
                case '&' -> n == '&' ? new Token(TT.AND2,       "&&", line) : null;
                case '|' -> n == '|' ? new Token(TT.OR2,        "||", line) : null;
                default -> null;
            };
            if (two != null) { out.add(two); i += 2; continue; }
            // Single-char
            TT tt = switch (c) {
                case '+' -> TT.PLUS;  case '-' -> TT.MINUS; case '*' -> TT.STAR;
                case '/' -> TT.SLASH; case '%' -> TT.PCT;   case '<' -> TT.LT;
                case '>' -> TT.GT;    case '=' -> TT.EQ;    case '!' -> TT.BANG;
                case '(' -> TT.LPAR;  case ')' -> TT.RPAR;  case '{' -> TT.LBRACE;
                case '}' -> TT.RBRACE; case '[' -> TT.LBRACK; case ']' -> TT.RBRACK;
                case ';' -> TT.SEMI; case ',' -> TT.COMMA;
                case '?' -> TT.QMARK; case ':' -> TT.COLON;
                default -> throw new RuntimeException("Unexpected char '" + c + "' line " + line);
            };
            out.add(new Token(tt, String.valueOf(c), line));
            i++;
        }
        out.add(new Token(TT.EOF, "", line));
        return out;
    }

    // ── AST ───────────────────────────────────────────────────────────────────

    sealed interface Expr permits
            Expr.Num, Expr.Bool, Expr.Var, Expr.BinOp, Expr.UnOp,
            Expr.Call, Expr.Ternary, Expr.PostInc, Expr.PostDec, Expr.Assign,
            Expr.ArrayLit, Expr.Index, Expr.IndexAssign {
        record Num    (double v)                              implements Expr {}
        record Bool   (boolean v)                            implements Expr {}
        record Var    (String name)                          implements Expr {}
        record BinOp  (String op, Expr l, Expr r)            implements Expr {}
        record UnOp   (String op, Expr e)                    implements Expr {}
        record Call   (String name, List<Expr> args)         implements Expr {}
        record Ternary(Expr cond, Expr then, Expr els)       implements Expr {}
        record PostInc(String name)                          implements Expr {}
        record PostDec(String name)                          implements Expr {}
        record Assign (String name, String op, Expr val)     implements Expr {}
        // Phase 10 — bounded arrays. ArrayLit is legal only as the RHS of a
        // `var` declaration; Index is a generic scalar-valued expression
        // {@code xs[i]}; IndexAssign updates an element {@code xs[i] = v}
        // (also +=, -=, *=, /=) and returns the new element value.
        record ArrayLit   (List<Expr> elems)                          implements Expr {}
        record Index      (String arrName, Expr idx)                  implements Expr {}
        record IndexAssign(String arrName, Expr idx, String op, Expr val) implements Expr {}
    }

    sealed interface Stmt permits Stmt.Decl, Stmt.ExprS, Stmt.For, Stmt.While,
                                  Stmt.If, Stmt.Return, Stmt.FuncDef {
        record Decl  (String name, Expr init)                            implements Stmt {}
        record ExprS (Expr expr)                                         implements Stmt {}
        record For   (String var, Expr init, Expr cond, Expr upd,
                      List<Stmt> body)                                   implements Stmt {}
        // Phase 10 — `while (cond) { body }`. MAX_ITERATIONS bounds the loop
        // regardless of construct, so allowing while is a parser-level
        // convenience with no new safety surface.
        record While (Expr cond, List<Stmt> body)                        implements Stmt {}
        record If    (Expr cond, List<Stmt> then, List<Stmt> els)        implements Stmt {}
        record Return(Expr val)                                          implements Stmt {}
        // Phase 8 Tier 2 — top-level user-defined function. The parser only
        // accepts these in parseProgram(); they are illegal inside if/for/
        // function bodies so all definitions are hoistable in one scan.
        record FuncDef(String name, List<String> params, List<Stmt> body) implements Stmt {}
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    static class Parser {
        final List<Token> tokens;
        int pos;
        Parser(List<Token> tokens) { this.tokens = tokens; }

        Token peek()       { return tokens.get(pos); }
        Token advance()    { return tokens.get(pos++); }
        boolean at(TT t)   { return peek().t() == t; }
        Token expect(TT t) {
            if (!at(t)) throw new RuntimeException(
                    "Expected " + t + " got '" + peek().v() + "' line " + peek().line());
            return advance();
        }
        void eatSemi() { if (at(TT.SEMI)) advance(); }

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!at(TT.EOF)) {
                if (at(TT.FUNCTION)) out.add(funcDef());
                else                 out.add(stmt());
            }
            return out;
        }

        Stmt stmt() {
            // Phase 8 Tier 2 — function definitions are top-level only. Inside
            // a block they would not be hoistable; reject them at parse time
            // with a clear message rather than silently allow shadowed scoping.
            if (at(TT.FUNCTION)) throw new RuntimeException(
                    "function definitions only allowed at top level, line "
                    + peek().line());
            if (at(TT.VAR)) return decl();
            if (at(TT.FOR)) return forStmt();
            if (at(TT.WHILE)) return whileStmt();
            if (at(TT.IF))  return ifStmt();
            if (at(TT.RET)) return retStmt();
            return exprStmt();
        }

        Stmt whileStmt() {
            advance(); // while
            expect(TT.LPAR); Expr cond = expr(); expect(TT.RPAR);
            List<Stmt> body = block();
            return new Stmt.While(cond, body);
        }

        Stmt funcDef() {
            advance(); // function
            String name = expect(TT.IDENT).v();
            expect(TT.LPAR);
            List<String> params = new ArrayList<>();
            if (!at(TT.RPAR)) {
                params.add(expect(TT.IDENT).v());
                while (at(TT.COMMA)) { advance(); params.add(expect(TT.IDENT).v()); }
            }
            expect(TT.RPAR);
            List<Stmt> body = block();
            return new Stmt.FuncDef(name, params, body);
        }

        Stmt decl() {
            advance(); // var
            String name = expect(TT.IDENT).v();
            expect(TT.EQ);
            Expr init = expr();
            eatSemi();
            return new Stmt.Decl(name, init);
        }

        Stmt forStmt() {
            advance(); // for
            expect(TT.LPAR);
            if (at(TT.VAR)) advance(); // optional var
            String varName = expect(TT.IDENT).v();
            expect(TT.EQ);
            Expr init = expr();
            expect(TT.SEMI);
            Expr cond = expr();
            expect(TT.SEMI);
            Expr upd  = updateExpr();
            expect(TT.RPAR);
            List<Stmt> body = block();
            return new Stmt.For(varName, init, cond, upd, body);
        }

        // Parse a for-update: n++, n--, n=n+1, n+=1, ++n, --n
        Expr updateExpr() {
            // ++n or --n
            if (at(TT.PLUSPLUS))   { advance(); String n = expect(TT.IDENT).v(); return new Expr.PostInc(n); }
            if (at(TT.MINUSMINUS)) { advance(); String n = expect(TT.IDENT).v(); return new Expr.PostDec(n); }
            // n++ or n-- or n=/+=/-= ...
            if (at(TT.IDENT)) {
                int save = pos;
                String name = advance().v();
                if (at(TT.PLUSPLUS))   { advance(); return new Expr.PostInc(name); }
                if (at(TT.MINUSMINUS)) { advance(); return new Expr.PostDec(name); }
                String op = null;
                if (at(TT.EQ))       { advance(); op = "="; }
                else if (at(TT.PLUS_EQ))  { advance(); op = "+="; }
                else if (at(TT.MINUS_EQ)) { advance(); op = "-="; }
                else if (at(TT.STAR_EQ))  { advance(); op = "*="; }
                else if (at(TT.SLASH_EQ)) { advance(); op = "/="; }
                if (op != null) return new Expr.Assign(name, op, expr());
                pos = save; // backtrack
            }
            return expr();
        }

        Stmt ifStmt() {
            advance(); // if
            expect(TT.LPAR); Expr cond = expr(); expect(TT.RPAR);
            List<Stmt> then = block();
            List<Stmt> els  = at(TT.ELSE) ? (advance() != null ? block() : List.of()) : List.of();
            return new Stmt.If(cond, then, els);
        }

        Stmt retStmt() {
            advance(); // return
            Expr val = (at(TT.SEMI) || at(TT.RBRACE) || at(TT.EOF)) ? new Expr.Num(0) : expr();
            eatSemi();
            return new Stmt.Return(val);
        }

        Stmt exprStmt() {
            if (at(TT.PLUSPLUS))   { advance(); return new Stmt.ExprS(new Expr.PostInc(expect(TT.IDENT).v())); }
            if (at(TT.MINUSMINUS)) { advance(); return new Stmt.ExprS(new Expr.PostDec(expect(TT.IDENT).v())); }
            if (at(TT.IDENT)) {
                int save = pos;
                String name = advance().v();
                Expr e = null;
                // Phase 10 — indexed assignment `xs[i] = v` / `xs[i] += v` etc.
                if (at(TT.LBRACK)) {
                    advance();
                    Expr idx = expr();
                    expect(TT.RBRACK);
                    String op = null;
                    if      (at(TT.EQ))       { advance(); op = "="; }
                    else if (at(TT.PLUS_EQ))  { advance(); op = "+="; }
                    else if (at(TT.MINUS_EQ)) { advance(); op = "-="; }
                    else if (at(TT.STAR_EQ))  { advance(); op = "*="; }
                    else if (at(TT.SLASH_EQ)) { advance(); op = "/="; }
                    if (op != null) {
                        e = new Expr.IndexAssign(name, idx, op, expr());
                    } else {
                        // `xs[i];` as a statement — read then discard. Wrap in Index.
                        e = new Expr.Index(name, idx);
                    }
                }
                else if (at(TT.PLUSPLUS))   { advance(); e = new Expr.PostInc(name); }
                else if (at(TT.MINUSMINUS)) { advance(); e = new Expr.PostDec(name); }
                else {
                    String op = null;
                    if      (at(TT.EQ))       { advance(); op = "="; }
                    else if (at(TT.PLUS_EQ))  { advance(); op = "+="; }
                    else if (at(TT.MINUS_EQ)) { advance(); op = "-="; }
                    else if (at(TT.STAR_EQ))  { advance(); op = "*="; }
                    else if (at(TT.SLASH_EQ)) { advance(); op = "/="; }
                    if (op != null) e = new Expr.Assign(name, op, expr());
                    else pos = save;
                }
                if (e != null) { eatSemi(); return new Stmt.ExprS(e); }
            }
            Stmt s = new Stmt.ExprS(expr()); eatSemi(); return s;
        }

        List<Stmt> block() {
            expect(TT.LBRACE);
            List<Stmt> out = new ArrayList<>();
            while (!at(TT.RBRACE) && !at(TT.EOF)) out.add(stmt());
            expect(TT.RBRACE);
            return out;
        }

        // Expressions (precedence climbing)
        Expr expr()    { return ternary(); }
        Expr ternary() {
            Expr e = or();
            if (!at(TT.QMARK)) return e;
            advance();
            Expr t = or(); expect(TT.COLON); Expr f = or();
            return new Expr.Ternary(e, t, f);
        }
        Expr or()  { Expr e = and();  while (at(TT.OR2))  { advance(); e = new Expr.BinOp("||", e, and());  } return e; }
        Expr and() { Expr e = cmp();  while (at(TT.AND2)) { advance(); e = new Expr.BinOp("&&", e, cmp());  } return e; }
        Expr cmp() {
            Expr e = add();
            if      (at(TT.LT))  { advance(); return new Expr.BinOp("<",  e, add()); }
            else if (at(TT.GT))  { advance(); return new Expr.BinOp(">",  e, add()); }
            else if (at(TT.LE))  { advance(); return new Expr.BinOp("<=", e, add()); }
            else if (at(TT.GE))  { advance(); return new Expr.BinOp(">=", e, add()); }
            else if (at(TT.EQ2)) { advance(); return new Expr.BinOp("==", e, add()); }
            else if (at(TT.NEQ)) { advance(); return new Expr.BinOp("!=", e, add()); }
            return e;
        }
        Expr add()  { Expr e = mul(); while (at(TT.PLUS) || at(TT.MINUS)) { String op = advance().v(); e = new Expr.BinOp(op, e, mul()); } return e; }
        Expr mul()  { Expr e = una(); while (at(TT.STAR) || at(TT.SLASH) || at(TT.PCT)) { String op = advance().v(); e = new Expr.BinOp(op, e, una()); } return e; }
        Expr una()  {
            if (at(TT.BANG))  { advance(); return new Expr.UnOp("!", una()); }
            if (at(TT.MINUS)) { advance(); return new Expr.UnOp("-", una()); }
            return pow();
        }
        Expr pow()  { Expr e = post(); if (at(TT.STARSTAR)) { advance(); return new Expr.BinOp("**", e, post()); } return e; }
        Expr post() {
            Expr e = prim();
            if (at(TT.PLUSPLUS)   && e instanceof Expr.Var v) { advance(); return new Expr.PostInc(v.name()); }
            if (at(TT.MINUSMINUS) && e instanceof Expr.Var v) { advance(); return new Expr.PostDec(v.name()); }
            // Phase 10 — indexed read `xs[i]`. Only valid when the primary is a
            // bare identifier (arrays are first-class but only live in named
            // bindings; you cannot index the result of an expression).
            if (at(TT.LBRACK) && e instanceof Expr.Var v) {
                advance();
                Expr idx = expr();
                expect(TT.RBRACK);
                return new Expr.Index(v.name(), idx);
            }
            return e;
        }
        Expr prim() {
            if (at(TT.NUM))   return new Expr.Num(Double.parseDouble(advance().v()));
            if (at(TT.TRUE))  { advance(); return new Expr.Bool(true);  }
            if (at(TT.FALSE)) { advance(); return new Expr.Bool(false); }
            if (at(TT.LPAR))  { advance(); Expr e = expr(); expect(TT.RPAR); return e; }
            // Phase 10 — array literal `[a, b, c, ...]`. Legal at any expression
            // position grammatically; the interpreter only accepts it as a
            // Decl RHS (anywhere else raises a runtime error) so the user
            // doesn't accidentally pass an array into a scalar context.
            if (at(TT.LBRACK)) {
                advance();
                List<Expr> elems = new ArrayList<>();
                if (!at(TT.RBRACK)) {
                    elems.add(expr());
                    while (at(TT.COMMA)) { advance(); elems.add(expr()); }
                }
                expect(TT.RBRACK);
                return new Expr.ArrayLit(elems);
            }
            if (at(TT.IDENT)) {
                String name = advance().v();
                if (at(TT.LPAR)) {
                    advance();
                    List<Expr> args = new ArrayList<>();
                    if (!at(TT.RPAR)) { args.add(expr()); while (at(TT.COMMA)) { advance(); args.add(expr()); } }
                    expect(TT.RPAR);
                    return new Expr.Call(name, args);
                }
                return new Expr.Var(name);
            }
            throw new RuntimeException("Unexpected token '" + peek().v() + "' line " + peek().line());
        }
    }

    // ── Interpreter ───────────────────────────────────────────────────────────

    static class ReturnSignal extends RuntimeException {
        final double value;
        ReturnSignal(double v) { super(null, null, true, false); this.value = v; }
    }

    static class LimitExceeded extends RuntimeException {
        LimitExceeded(String msg) { super(msg); }
    }

    static class Interpreter {
        /**
         * Global scope. Phase 10 — values are {@link Object} so a binding can
         * hold either a {@link Double} (scalar) or a {@link double double[]}
         * (bounded array). Numeric autoboxing keeps the existing
         * {@code env.put("n", (double) i)} call sites working unchanged.
         */
        final Map<String, Object> env = new HashMap<>();
        /**
         * Phase 8 Tier 2 — scope stack. The deepest (last) element is always
         * {@link #env} (global); function calls push a fresh frame on top with
         * parameter bindings, and {@code var} declarations land in the topmost
         * frame so locals never leak out of a function body.
         */
        final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
        /**
         * Phase 8 Tier 2 — user-defined functions registered at the start of
         * {@link #exec(List)} by a single hoisting pass. Same map lives for the
         * lifetime of the Interpreter so mutual recursion works regardless of
         * declaration order.
         */
        final Map<String, Stmt.FuncDef> userFunctions = new HashMap<>();

        int iterCount = 0;
        int stmtCount = 0;
        int callDepth = 0;

        // Phase 10 — recordSample aggregates. Updated by recordSample(v) calls;
        // read by buildResult() to populate the structured SandboxResult.
        int    recN    = 0;
        double recSum  = 0.0;
        double recSqSum = 0.0;
        double recMin  = Double.POSITIVE_INFINITY;
        double recMax  = Double.NEGATIVE_INFINITY;

        Interpreter(int start, int end, int param1, double param2) {
            env.put("start",  (double) start);
            env.put("end",    (double) end);
            env.put("param1", (double) param1);
            env.put("param2", param2);
            // Phase 5 A3 — math constants as read-only env entries (the LLM cannot
            // overwrite these inside sandboxed code because the script's `var` declarations
            // shadow rather than mutate the seeded env slots).
            env.put("PI", Math.PI);
            env.put("E",  Math.E);
            // Phase 8 Tier 1 — SI physical constants. Names are CAPS_SNAKE to avoid
            // collision with typical loop/scratch variables (n, c, h, k, e, sigma, b)
            // that the LLM will declare locally. Codata 2018 values.
            env.put("WIEN_B",               2.897_771_955e-3);   // m·K  (displacement law)
            env.put("STEFAN_BOLTZMANN_SIGMA", 5.670_374_419e-8); // W·m⁻²·K⁻⁴
            env.put("PLANCK_H",             6.626_070_15e-34);   // J·s
            env.put("PLANCK_HBAR",          1.054_571_817e-34);  // J·s
            env.put("BOLTZMANN_K",          1.380_649e-23);      // J/K
            env.put("SPEED_OF_LIGHT",       299_792_458.0);      // m/s
            env.put("AVOGADRO",             6.022_140_76e23);    // 1/mol
            env.put("ELEMENTARY_CHARGE",    1.602_176_634e-19);  // C
            env.put("GRAVITATIONAL_G",      6.674_30e-11);       // m³·kg⁻¹·s⁻²
            env.put("ELECTRON_MASS",        9.109_383_7015e-31); // kg
            env.put("PROTON_MASS",          1.672_621_923_69e-27); // kg
            env.put("VACUUM_PERMITTIVITY",  8.854_187_8128e-12); // F/m
            env.put("VACUUM_PERMEABILITY",  1.256_637_062_12e-6); // N/A²
            scopes.push(env);
        }

        // ── Scope access ─────────────────────────────────────────────────────

        /** Lookup walks inner→outer; missing names resolve to 0.0 (legacy semantic).
         *  Phase 10 — raises if the binding is an array (use {@link #readArray}). */
        double readVar(String name) {
            for (Map<String, Object> scope : scopes) {
                Object v = scope.get(name);
                if (v instanceof Double d) return d;
                if (v instanceof double[]) throw new RuntimeException(
                        "Cannot use array '" + name + "' as scalar");
                if (v != null) return 0.0; // future-proof; should not happen today
            }
            return 0.0;
        }

        /** Like {@link #readVar} but returns {@code null} when no scope owns the name
         *  OR the binding is an array. Used by Pattern C bridges to distinguish
         *  "declared, equal to 0" from "never declared, treat as a free Symja
         *  symbol or reject". Arrays don't participate in symbolic substitution. */
        Double tryReadVar(String name) {
            for (Map<String, Object> scope : scopes) {
                Object v = scope.get(name);
                if (v instanceof Double d) return d;
                if (v != null) return null; // array or other non-scalar binding
            }
            return null;
        }

        /** Phase 10 — array-typed lookup. Raises if the binding is missing or scalar. */
        double[] readArray(String name) {
            for (Map<String, Object> scope : scopes) {
                Object v = scope.get(name);
                if (v instanceof double[] arr) return arr;
                if (v != null) throw new RuntimeException(
                        "Variable '" + name + "' is a scalar, not an array");
            }
            throw new RuntimeException("Array '" + name + "' is not declared");
        }

        /**
         * Assign to an existing binding (walking inner→outer); if no scope owns
         * the name, create it in the current (top) frame. Matches JavaScript
         * implicit-global semantics, but bounded because the only way to add a
         * new frame is via a user-function call.
         */
        void writeVar(String name, double value) {
            for (Map<String, Object> scope : scopes) {
                Object existing = scope.get(name);
                if (existing instanceof double[]) throw new RuntimeException(
                        "Cannot assign scalar to array variable '" + name + "'");
                if (existing != null) { scope.put(name, value); return; }
            }
            scopes.peek().put(name, value);
        }

        /** {@code var} declarations always land in the innermost (top) frame. */
        void declareVar(String name, double value) {
            scopes.peek().put(name, value);
        }

        /** Phase 10 — declare an array binding in the innermost (top) frame. */
        void declareArray(String name, double[] arr) {
            scopes.peek().put(name, arr);
        }

        // ── Execution ────────────────────────────────────────────────────────

        double exec(List<Stmt> stmts) {
            // Phase 8 Tier 2 — single hoisting pass: register every FuncDef in
            // the user-function table before executing any top-level statements.
            // Lets functions call each other regardless of source order, and lets
            // top-level code call a function declared further down.
            for (Stmt s : stmts) {
                if (s instanceof Stmt.FuncDef fd) {
                    if (BUILTINS.contains(fd.name())) {
                        throw new RuntimeException(
                                "Cannot redefine builtin '" + fd.name()
                                + "' as a user function");
                    }
                    if (userFunctions.containsKey(fd.name())) {
                        throw new RuntimeException(
                                "Function '" + fd.name() + "' already defined");
                    }
                    userFunctions.put(fd.name(), fd);
                }
            }
            for (Stmt s : stmts) {
                if (!(s instanceof Stmt.FuncDef)) execStmt(s);
            }
            return 0.0;
        }

        void execStmt(Stmt s) {
            if (++stmtCount > MAX_STMTS) throw new LimitExceeded("Statement limit exceeded");
            switch (s) {
                case Stmt.Decl   d -> {
                    // Phase 10 — array literals are only legal as the RHS of a
                    // declaration. Everywhere else they raise.
                    if (d.init() instanceof Expr.ArrayLit lit) {
                        if (lit.elems().size() > MAX_ARRAY_LEN) {
                            throw new RuntimeException("Array length "
                                    + lit.elems().size() + " exceeds cap "
                                    + MAX_ARRAY_LEN);
                        }
                        double[] arr = new double[lit.elems().size()];
                        for (int i = 0; i < arr.length; i++) arr[i] = eval(lit.elems().get(i));
                        declareArray(d.name(), arr);
                    } else {
                        declareVar(d.name(), eval(d.init()));
                    }
                }
                case Stmt.ExprS  e -> eval(e.expr());
                case Stmt.Return r -> throw new ReturnSignal(eval(r.val()));
                case Stmt.If     f -> {
                    if (truthy(eval(f.cond()))) for (Stmt b : f.then()) execStmt(b);
                    else                         for (Stmt b : f.els())  execStmt(b);
                }
                case Stmt.For    f -> {
                    writeVar(f.var(), eval(f.init()));
                    while (truthy(eval(f.cond()))) {
                        if (++iterCount > MAX_ITERATIONS) throw new LimitExceeded("Loop limit exceeded");
                        for (Stmt b : f.body()) execStmt(b);
                        eval(f.upd());
                    }
                }
                case Stmt.While  w -> {
                    while (truthy(eval(w.cond()))) {
                        if (++iterCount > MAX_ITERATIONS) throw new LimitExceeded("Loop limit exceeded");
                        for (Stmt b : w.body()) execStmt(b);
                    }
                }
                // Hoisted in exec(); ignored if it survives to this point.
                case Stmt.FuncDef fd -> { /* no-op */ }
            }
        }

        double eval(Expr e) {
            return switch (e) {
                case Expr.Num     n -> n.v();
                case Expr.Bool    b -> b.v() ? 1.0 : 0.0;
                case Expr.Var     v -> readVar(v.name());
                case Expr.Ternary t -> truthy(eval(t.cond())) ? eval(t.then()) : eval(t.els());
                case Expr.PostInc p -> { double v = readVar(p.name()); writeVar(p.name(), v + 1); yield v; }
                case Expr.PostDec p -> { double v = readVar(p.name()); writeVar(p.name(), v - 1); yield v; }
                case Expr.Assign  a -> {
                    double rhs = eval(a.val()), cur = readVar(a.name());
                    double res = switch (a.op()) {
                        case "="  -> rhs;
                        case "+=" -> cur + rhs;
                        case "-=" -> cur - rhs;
                        case "*=" -> cur * rhs;
                        case "/=" -> rhs == 0 ? 0 : cur / rhs;
                        default   -> rhs;
                    };
                    writeVar(a.name(), res); yield res;
                }
                case Expr.UnOp   u -> switch (u.op()) {
                    case "!" -> truthy(eval(u.e())) ? 0.0 : 1.0;
                    case "-" -> -eval(u.e());
                    default  -> eval(u.e());
                };
                case Expr.BinOp  b -> {
                    if ("&&".equals(b.op())) yield truthy(eval(b.l())) && truthy(eval(b.r())) ? 1.0 : 0.0;
                    if ("||".equals(b.op())) yield truthy(eval(b.l())) || truthy(eval(b.r())) ? 1.0 : 0.0;
                    double l = eval(b.l()), r = eval(b.r());
                    yield switch (b.op()) {
                        case "+"  -> l + r;   case "-"  -> l - r;   case "*"  -> l * r;
                        case "/"  -> r == 0 ? 0.0 : l / r;
                        case "%"  -> r == 0 ? 0.0 : l % r;
                        case "**" -> Math.pow(l, r);
                        case "<"  -> l <  r ? 1.0 : 0.0; case ">"  -> l >  r ? 1.0 : 0.0;
                        case "<=" -> l <= r ? 1.0 : 0.0; case ">=" -> l >= r ? 1.0 : 0.0;
                        case "==" -> l == r ? 1.0 : 0.0; case "!=" -> l != r ? 1.0 : 0.0;
                        default   -> 0.0;
                    };
                }
                case Expr.Call   c -> evalCall(c.name(), c.args());
                // Phase 10 — array literals outside a Decl RHS raise; Decl
                // handles them directly (see execStmt). This branch only fires
                // if an ArrayLit shows up as a sub-expression, which is illegal.
                case Expr.ArrayLit a -> throw new RuntimeException(
                        "Array literal can only appear as the RHS of a `var` declaration");
                case Expr.Index ix -> {
                    double[] arr = readArray(ix.arrName());
                    int idx = (int) eval(ix.idx());
                    if (idx < 0 || idx >= arr.length) {
                        throw new RuntimeException("Array '" + ix.arrName()
                                + "' index " + idx + " out of bounds [0,"
                                + arr.length + ")");
                    }
                    yield arr[idx];
                }
                case Expr.IndexAssign ix -> {
                    double[] arr = readArray(ix.arrName());
                    int idx = (int) eval(ix.idx());
                    if (idx < 0 || idx >= arr.length) {
                        throw new RuntimeException("Array '" + ix.arrName()
                                + "' index " + idx + " out of bounds [0,"
                                + arr.length + ")");
                    }
                    double rhs = eval(ix.val());
                    double cur = arr[idx];
                    double res = switch (ix.op()) {
                        case "="  -> rhs;
                        case "+=" -> cur + rhs;
                        case "-=" -> cur - rhs;
                        case "*=" -> cur * rhs;
                        case "/=" -> rhs == 0 ? 0 : cur / rhs;
                        default   -> rhs;
                    };
                    arr[idx] = res;
                    yield res;
                }
            };
        }

        boolean truthy(double v) { return v != 0.0 && !Double.isNaN(v); }

        double evalCall(String name, List<Expr> rawArgs) {
            // Phase 8 Tier 2 — user-defined functions are looked up before
            // builtins. Definition-time shadowing of a builtin is rejected
            // by exec(), so reaching this branch means the name is a real
            // user function (no ambiguity at call time).
            Stmt.FuncDef userFn = userFunctions.get(name);
            if (userFn != null) return invokeUserFunction(userFn, rawArgs);

            // Phase 8 Pattern C — bridge builtins consume their argument
            // expressions as ASTs (unevaluated) and translate them into Symja.
            // Must run BEFORE the double[]-args path below, because the
            // expressions inside `integrate(sin(n)**2, n, 0, PI)` are symbolic,
            // not numeric.
            if (BRIDGE_BUILTINS.contains(name)) return evalBridgeBuiltin(name, rawArgs);

            if (!BUILTINS.contains(name)) throw new RuntimeException("Unknown function: " + name);

            // Phase 10 — `len(arr)` reads the array binding directly. It must
            // run BEFORE the generic argument evaluator below, because that
            // evaluator would call eval() on the Var holding the array, which
            // raises "cannot use array as scalar".
            if ("len".equals(name)) {
                if (rawArgs.size() != 1 || !(rawArgs.get(0) instanceof Expr.Var v)) {
                    throw new RuntimeException(
                            "len(arr) expects a single array-variable argument");
                }
                return readArray(v.name()).length;
            }

            double[] a = rawArgs.stream().mapToDouble(this::eval).toArray();

            // Phase 10 — recordSample needs instance state, so it short-circuits
            // before the static evalSpecialBuiltin dispatch.
            if ("recordSample".equals(name)) {
                if (a.length < 1) throw new RuntimeException(
                        "recordSample expects 1 arg (the sample value)");
                double v = a[0];
                recN++;
                recSum   += v;
                recSqSum += v * v;
                if (v < recMin) recMin = v;
                if (v > recMax) recMax = v;
                return v;
            }

            // ── 1. Domain-specific (non-Math) helpers ─────────────────────────
            Double special = evalSpecialBuiltin(name, a);
            if (special != null) return special;

            // ── 2. Reflective java.lang.Math dispatch ─────────────────────────
            Method m = MATH_METHODS.get(name);
            if (m != null) {
                int needed = m.getParameterCount();
                if (a.length < needed) {
                    throw new RuntimeException("Function '" + name + "' expects "
                            + needed + " arg(s), got " + a.length);
                }
                Object[] boxed = new Object[needed];
                for (int i = 0; i < needed; i++) boxed[i] = a[i];
                try {
                    return (double) m.invoke(null, boxed);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Math." + name + " call failed: "
                            + e.getMessage(), e);
                }
            }
            // Cannot happen — BUILTINS gates entry — but keep a defensive throw.
            throw new RuntimeException("Unimplemented builtin: " + name);
        }

        /**
         * Phase 8 Tier 2 — execute a user-defined function with **lexical
         * scoping**. Arguments evaluate in the caller's scope; the new frame
         * holds only the parameter bindings; intermediate caller frames are
         * temporarily stripped so the function body sees only its params and
         * the global frame.
         *
         * <p>Lexical-only scoping prevents the surprise where {@code function
         * inner()} would read a local variable that happens to be named the
         * same in the caller. With dynamic scoping that's a hard-to-spot
         * aliasing bug; with lexical scoping it's just a read of {@code 0}.
         * The caller-frame stack is restored on return so deeper recursion
         * still composes correctly.
         */
        double invokeUserFunction(Stmt.FuncDef fd, List<Expr> rawArgs) {
            if (rawArgs.size() != fd.params().size()) {
                throw new RuntimeException("Function '" + fd.name() + "' expects "
                        + fd.params().size() + " arg(s), got " + rawArgs.size());
            }
            double[] argValues = new double[rawArgs.size()];
            for (int i = 0; i < rawArgs.size(); i++) argValues[i] = eval(rawArgs.get(i));

            if (++callDepth > MAX_CALL_DEPTH) {
                callDepth--; // rewind so the throw doesn't lock the counter
                throw new LimitExceeded("Call depth limit (" + MAX_CALL_DEPTH
                        + ") exceeded in '" + fd.name() + "'");
            }
            Map<String, Object> frame = new HashMap<>();
            for (int i = 0; i < fd.params().size(); i++) {
                frame.put(fd.params().get(i), argValues[i]);
            }
            // Strip every frame above global, push the function frame, execute,
            // then restore. While the function body runs, readVar/writeVar walk
            // only [frame, global] — no caller locals leak in.
            List<Map<String, Object>> stashed = new ArrayList<>();
            while (scopes.size() > 1) stashed.add(scopes.pop());
            try {
                scopes.push(frame);
                for (Stmt b : fd.body()) execStmt(b);
                return 0.0;          // function ended without explicit return
            } catch (ReturnSignal r) {
                return r.value;       // function-local return; do not propagate
            } finally {
                scopes.pop();         // frame
                for (int i = stashed.size() - 1; i >= 0; i--) scopes.push(stashed.get(i));
                callDepth--;
            }
        }

        /**
         * Integer-typed and number-theoretic helpers that don't fit the generic
         * {@code Math.*} double-in/double-out shape. Returns {@code null} when
         * the name isn't one of these, so the caller falls through to reflection.
         */
        private static Double evalSpecialBuiltin(String name, double[] a) {
            int a0 = a.length > 0 ? (int) a[0] : 0;
            return switch (name) {
                case "isPrime"           -> MathPhysicsEngine.isPrime(a0) ? 1.0 : 0.0;
                case "isHappy"           -> MathPhysicsEngine.isHappy(a0) ? 1.0 : 0.0;
                case "collatzTerminates" -> MathPhysicsEngine.collatzSteps(a0) < 10_000 ? 1.0 : 0.0;
                case "isAbundant"        -> MathPhysicsEngine.sigmaSum(a0) > a0 ? 1.0 : 0.0;
                case "isPerfect"         -> MathPhysicsEngine.sigmaSum(a0) == a0 ? 1.0 : 0.0;
                case "collatzSteps"      -> (double) MathPhysicsEngine.collatzSteps(a0);
                case "sigmaSum"          -> (double) MathPhysicsEngine.sigmaSum(a0);
                case "gcd"               -> gcd(a.length > 0 ? (long) a[0] : 0L,
                                                 a.length > 1 ? (long) a[1] : 0L);
                case "log2"              -> Math.log(a[0]) / Math.log(2);
                case "signedDevPercent"  -> {
                    if (a.length < 2) throw new RuntimeException(
                            "signedDevPercent(actual, expected) requires 2 arguments");
                    double exp = a[1];
                    if (exp == 0.0) yield 0.0;
                    yield (a[0] - exp) / Math.abs(exp) * 100.0;
                }
                // Phase 8 Tier 1 — number-theory helpers backing F3.4 MATHEMATICS topics.
                case "mobius"             -> (double) MathPhysicsEngine.mobius(a0);
                case "sternBrocotDepthOf" -> (double) MathPhysicsEngine.sternBrocotDepthOf(
                                                a0, a.length > 1 ? (int) a[1] : 1);
                case "isSquarefree"       -> MathPhysicsEngine.isSquarefree(a0) ? 1.0 : 0.0;
                case "mertensPartialSum"  -> (double) MathPhysicsEngine.mertensPartialSum(a0);
                default -> null;
            };
        }

        /**
         * Phase 10 — build a {@link SandboxResult} for this interpreter run.
         * Populates the recordSample aggregates if any samples were recorded;
         * otherwise returns the legacy two-field shape.
         */
        SandboxResult buildResult(double value) {
            if (recN == 0) {
                return new SandboxResult(value,
                        "result=%.6g  iters_used=%d".formatted(value, iterCount));
            }
            double mean = recSum / recN;
            double variance = recN > 1 ? Math.max(0, recSqSum / recN - mean * mean) : 0;
            double std = Math.sqrt(variance);
            String summary = ("result=%.6g  iters_used=%d  "
                    + "recorded n=%d mean=%.4g std=%.4g min=%.4g max=%.4g")
                    .formatted(value, iterCount, recN, mean, std, recMin, recMax);
            return new SandboxResult(value, summary, recN, mean, std, recMin, recMax);
        }

        private static double gcd(long a, long b) {
            a = Math.abs(a); b = Math.abs(b);
            while (b != 0) { long t = b; b = a % b; a = t; }
            return a;
        }

        // ── Phase 8 Pattern C — Symja bridges ────────────────────────────────

        /**
         * Bridge dispatch. The arg list arrives as raw {@link Expr} subtrees
         * (not evaluated to doubles) so the AST can be translated to Symja
         * with the integration variable preserved as a Symja symbol.
         *
         * <p>{@link SymbolicEngine#evaluate(String)} handles wall-clock and
         * iteration caps internally — the bridge inherits both. The Symja
         * result is coerced to a double via the same {@code numericApprox}
         * path used by {@code run_symbolic}; symbolic-only results raise.
         */
        double evalBridgeBuiltin(String name, List<Expr> rawArgs) {
            return switch (name) {
                case "integrate" -> {
                    if (rawArgs.size() != 4) {
                        throw new RuntimeException(
                                "integrate expects 4 args (expr, var, lo, hi), got " + rawArgs.size());
                    }
                    String var = requireBareVar(rawArgs.get(1), "integrate");
                    Set<String> bound = Set.of(var);
                    String exprStr = toSymja(rawArgs.get(0), bound, false);
                    String loStr   = toSymja(rawArgs.get(2), bound, false);
                    String hiStr   = toSymja(rawArgs.get(3), bound, false);
                    yield invokeBridge(name,
                            "N[Integrate[" + exprStr + ", {" + var + ", " + loStr + ", " + hiStr + "}]]");
                }
                case "differentiate" -> {
                    if (rawArgs.size() != 3) {
                        throw new RuntimeException(
                                "differentiate expects 3 args (expr, var, at), got " + rawArgs.size());
                    }
                    String var = requireBareVar(rawArgs.get(1), "differentiate");
                    Set<String> bound = Set.of(var);
                    String exprStr = toSymja(rawArgs.get(0), bound, false);
                    String atStr   = toSymja(rawArgs.get(2), bound, false);
                    yield invokeBridge(name,
                            "N[D[" + exprStr + ", " + var + "] /. " + var + " -> (" + atStr + ")]");
                }
                case "simplify" -> {
                    if (rawArgs.size() != 1) {
                        throw new RuntimeException(
                                "simplify expects 1 arg (expr), got " + rawArgs.size());
                    }
                    // simplify accepts free symbolic variables — that's the use case
                    // (verifying identities like sin(x)^2 + cos(x)^2 → 1). If Symja
                    // can't reduce the expression to a finite number, invokeBridge
                    // surfaces a "simplify produced a non-numeric result" error.
                    String exprStr = toSymja(rawArgs.get(0), Set.of(), true);
                    yield invokeBridge(name, "N[Simplify[" + exprStr + "]]");
                }
                default -> throw new RuntimeException("Unimplemented bridge: " + name);
            };
        }

        private double invokeBridge(String bridgeName, String symjaExpression) {
            SymbolicEngine.SymbolicResult r;
            try {
                r = SymbolicEngine.evaluate(symjaExpression);
            } catch (RuntimeException ex) {
                throw new RuntimeException(
                        bridgeName + " bridge failed: " + ex.getMessage(), ex);
            }
            if (r.numericApprox() == null) {
                throw new RuntimeException(
                        bridgeName + " produced a non-numeric result (" + r.output()
                                + "). Bridge builtins must coerce to a finite number; "
                                + "use run_symbolic directly for symbolic outputs.");
            }
            return r.numericApprox();
        }

        private static String requireBareVar(Expr e, String fnName) {
            if (e instanceof Expr.Var v) return v.name();
            throw new RuntimeException(
                    fnName + ": variable argument must be a bare identifier (e.g. `x`), "
                            + "got " + e.getClass().getSimpleName());
        }

        /**
         * Translate a sandbox AST subtree to a Symja expression string. The
         * caller passes:
         * <ul>
         *   <li>{@code boundVars} — names treated verbatim as Symja symbols
         *       (the integration / differentiation variable, set per bridge).</li>
         *   <li>{@code allowFreeSymbols} — when true, any name not in the
         *       scope stack passes through as a Symja symbol (used by
         *       {@code simplify} so identities like
         *       {@code sin(x)^2 + cos(x)^2} → 1 work without declaring x).
         *       When false, unknown names are rejected.</li>
         * </ul>
         *
         * <p>Names that ARE in the scope stack get their numeric value
         * substituted regardless — so sandbox-side {@code WIEN_B} or a local
         * {@code lo} variable becomes a literal in the Symja expression.
         */
        String toSymja(Expr e, Set<String> boundVars, boolean allowFreeSymbols) {
            return switch (e) {
                case Expr.Num n  -> formatSymjaNum(n.v());
                case Expr.Bool b -> b.v() ? "True" : "False";
                case Expr.Var v -> {
                    if (boundVars.contains(v.name())) yield v.name();
                    Double scopeVal = tryReadVar(v.name());
                    if (scopeVal != null) yield formatSymjaNum(scopeVal);
                    if (allowFreeSymbols) yield v.name();
                    throw new RuntimeException(
                            "Bridge expression references unbound variable '" + v.name()
                                    + "'. Either pass it as the bound variable arg "
                                    + "(e.g. integrate(_, " + v.name() + ", _, _)) or "
                                    + "declare it with `var` before the call so its "
                                    + "numeric value is substituted.");
                }
                case Expr.UnOp u -> "(-" + toSymja(u.e(), boundVars, allowFreeSymbols) + ")";
                case Expr.BinOp b -> {
                    String ls = toSymja(b.l(), boundVars, allowFreeSymbols);
                    String rs = toSymja(b.r(), boundVars, allowFreeSymbols);
                    yield switch (b.op()) {
                        case "+"  -> "(" + ls + " + " + rs + ")";
                        case "-"  -> "(" + ls + " - " + rs + ")";
                        case "*"  -> "(" + ls + " * " + rs + ")";
                        case "/"  -> "(" + ls + " / " + rs + ")";
                        case "**" -> "Power[" + ls + ", " + rs + "]";
                        case "%"  -> "Mod["   + ls + ", " + rs + "]";
                        default -> throw new RuntimeException(
                                "Operator '" + b.op() + "' not supported in bridge expressions");
                    };
                }
                case Expr.Call c -> {
                    if (BRIDGE_BUILTINS.contains(c.name())) {
                        throw new RuntimeException(
                                "Cannot nest bridge call '" + c.name() + "' inside another bridge");
                    }
                    // hypot has no Symja head; expand inline as Sqrt[a^2 + b^2].
                    if ("hypot".equals(c.name())) {
                        if (c.args().size() != 2) {
                            throw new RuntimeException("hypot expects 2 args");
                        }
                        String ha = toSymja(c.args().get(0), boundVars, allowFreeSymbols);
                        String hb = toSymja(c.args().get(1), boundVars, allowFreeSymbols);
                        yield "Sqrt[Power[" + ha + ", 2] + Power[" + hb + ", 2]]";
                    }
                    String head = symjaHead(c.name());
                    if (head == null) {
                        throw new RuntimeException(
                                "Bridge expression cannot use function '" + c.name()
                                        + "'. Allowed: sqrt, cbrt, exp, log, log2, log10, "
                                        + "sin, cos, tan, asin, acos, atan, atan2, "
                                        + "sinh, cosh, tanh, abs, floor, ceil, pow, hypot, min, max.");
                    }
                    StringBuilder sb = new StringBuilder(head).append('[');
                    for (int i = 0; i < c.args().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(toSymja(c.args().get(i), boundVars, allowFreeSymbols));
                    }
                    sb.append(']');
                    yield sb.toString();
                }
                default -> throw new RuntimeException(
                        "Bridge expression cannot contain " + e.getClass().getSimpleName());
            };
        }

        private static String symjaHead(String sandboxFnName) {
            return switch (sandboxFnName) {
                case "sqrt"  -> "Sqrt";
                case "cbrt"  -> "Surd";   // Surd[x, 3] is the real cube root.
                case "exp"   -> "Exp";
                case "log"   -> "Log";
                case "log2"  -> "Log2";
                case "log10" -> "Log10";
                case "sin"   -> "Sin";   case "cos"  -> "Cos";   case "tan"  -> "Tan";
                case "asin"  -> "ArcSin"; case "acos" -> "ArcCos"; case "atan" -> "ArcTan";
                case "atan2" -> "ArcTan";   // ArcTan[x, y] is the two-arg form.
                case "sinh"  -> "Sinh";  case "cosh" -> "Cosh";  case "tanh" -> "Tanh";
                case "abs"   -> "Abs";   case "floor" -> "Floor"; case "ceil" -> "Ceiling";
                case "pow"   -> "Power";
                case "min"   -> "Min";   case "max"  -> "Max";
                // hypot is handled inline in toSymja (Symja has no Hypot head).
                default -> null;
            };
        }

        private static String formatSymjaNum(double v) {
            if (v == Math.floor(v) && !Double.isInfinite(v)
                    && Math.abs(v) < 1e15 && !Double.isNaN(v)) {
                return Long.toString((long) v);
            }
            return Double.toString(v);
        }
    }
}
