package com.emergentterrain.research;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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

    static final int MAX_ITERATIONS = 5_000;
    static final int MAX_STMTS      = 50_000;

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
            "mobius", "sternBrocotDepthOf", "isSquarefree", "mertensPartialSum");

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
        return Set.copyOf(all);
    }

    public record SandboxResult(double value, String summary) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static SandboxResult run(String code, int start, int end, int param1, double param2) {
        try {
            List<Token> tokens  = lex(code);
            List<Stmt>  program = new Parser(tokens).parseProgram();
            Interpreter interp  = new Interpreter(start, end, param1, param2);
            double result = interp.exec(program);
            return new SandboxResult(result,
                    "result=%.6g  iters_used=%d".formatted(result, interp.iterCount));
        } catch (ReturnSignal r) {
            return new SandboxResult(r.value,
                    "result=%.6g".formatted(r.value));
        }
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
        EQ, SEMI, COMMA, LPAR, RPAR, LBRACE, RBRACE, QMARK, COLON,
        IF, ELSE, FOR, VAR, RET, TRUE, FALSE,
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
                out.add(new Token(TT.NUM, src.substring(s, i), line));
                continue;
            }
            // Identifier / keyword
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                String w = src.substring(s, i);
                TT t = switch (w) {
                    case "if"     -> TT.IF;   case "else"   -> TT.ELSE; case "for" -> TT.FOR;
                    case "var"    -> TT.VAR;  case "return" -> TT.RET;
                    case "true"   -> TT.TRUE; case "false"  -> TT.FALSE;
                    default -> TT.IDENT;
                };
                out.add(new Token(t, w, line));
                continue;
            }
            // Two-char operators
            char n = (i + 1 < src.length()) ? src.charAt(i + 1) : 0;
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
                case '}' -> TT.RBRACE; case ';' -> TT.SEMI; case ',' -> TT.COMMA;
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
            Expr.Call, Expr.Ternary, Expr.PostInc, Expr.PostDec, Expr.Assign {
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
    }

    sealed interface Stmt permits Stmt.Decl, Stmt.ExprS, Stmt.For, Stmt.If, Stmt.Return {
        record Decl  (String name, Expr init)                            implements Stmt {}
        record ExprS (Expr expr)                                         implements Stmt {}
        record For   (String var, Expr init, Expr cond, Expr upd,
                      List<Stmt> body)                                   implements Stmt {}
        record If    (Expr cond, List<Stmt> then, List<Stmt> els)        implements Stmt {}
        record Return(Expr val)                                          implements Stmt {}
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
            while (!at(TT.EOF)) out.add(stmt());
            return out;
        }

        Stmt stmt() {
            if (at(TT.VAR)) return decl();
            if (at(TT.FOR)) return forStmt();
            if (at(TT.IF))  return ifStmt();
            if (at(TT.RET)) return retStmt();
            return exprStmt();
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
                if      (at(TT.PLUSPLUS))   { advance(); e = new Expr.PostInc(name); }
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
            return e;
        }
        Expr prim() {
            if (at(TT.NUM))   return new Expr.Num(Double.parseDouble(advance().v()));
            if (at(TT.TRUE))  { advance(); return new Expr.Bool(true);  }
            if (at(TT.FALSE)) { advance(); return new Expr.Bool(false); }
            if (at(TT.LPAR))  { advance(); Expr e = expr(); expect(TT.RPAR); return e; }
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
        final Map<String, Double> env = new HashMap<>();
        int iterCount = 0;
        int stmtCount = 0;

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
        }

        double exec(List<Stmt> stmts) {
            for (Stmt s : stmts) execStmt(s);
            return 0.0;
        }

        void execStmt(Stmt s) {
            if (++stmtCount > MAX_STMTS) throw new LimitExceeded("Statement limit exceeded");
            switch (s) {
                case Stmt.Decl   d -> env.put(d.name(), eval(d.init()));
                case Stmt.ExprS  e -> eval(e.expr());
                case Stmt.Return r -> throw new ReturnSignal(eval(r.val()));
                case Stmt.If     f -> {
                    if (truthy(eval(f.cond()))) for (Stmt b : f.then()) execStmt(b);
                    else                         for (Stmt b : f.els())  execStmt(b);
                }
                case Stmt.For    f -> {
                    env.put(f.var(), eval(f.init()));
                    while (truthy(eval(f.cond()))) {
                        if (++iterCount > MAX_ITERATIONS) throw new LimitExceeded("Loop limit exceeded");
                        for (Stmt b : f.body()) execStmt(b);
                        eval(f.upd());
                    }
                }
            }
        }

        double eval(Expr e) {
            return switch (e) {
                case Expr.Num     n -> n.v();
                case Expr.Bool    b -> b.v() ? 1.0 : 0.0;
                case Expr.Var     v -> env.getOrDefault(v.name(), 0.0);
                case Expr.Ternary t -> truthy(eval(t.cond())) ? eval(t.then()) : eval(t.els());
                case Expr.PostInc p -> { double v = env.getOrDefault(p.name(), 0.0); env.put(p.name(), v + 1); yield v; }
                case Expr.PostDec p -> { double v = env.getOrDefault(p.name(), 0.0); env.put(p.name(), v - 1); yield v; }
                case Expr.Assign  a -> {
                    double rhs = eval(a.val()), cur = env.getOrDefault(a.name(), 0.0);
                    double res = switch (a.op()) {
                        case "="  -> rhs;
                        case "+=" -> cur + rhs;
                        case "-=" -> cur - rhs;
                        case "*=" -> cur * rhs;
                        case "/=" -> rhs == 0 ? 0 : cur / rhs;
                        default   -> rhs;
                    };
                    env.put(a.name(), res); yield res;
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
            };
        }

        boolean truthy(double v) { return v != 0.0 && !Double.isNaN(v); }

        double evalCall(String name, List<Expr> rawArgs) {
            if (!BUILTINS.contains(name)) throw new RuntimeException("Unknown function: " + name);
            double[] a = rawArgs.stream().mapToDouble(this::eval).toArray();

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
                // Phase 8 Tier 1 — number-theory helpers backing F3.4 MATHEMATICS topics.
                case "mobius"             -> (double) MathPhysicsEngine.mobius(a0);
                case "sternBrocotDepthOf" -> (double) MathPhysicsEngine.sternBrocotDepthOf(
                                                a0, a.length > 1 ? (int) a[1] : 1);
                case "isSquarefree"       -> MathPhysicsEngine.isSquarefree(a0) ? 1.0 : 0.0;
                case "mertensPartialSum"  -> (double) MathPhysicsEngine.mertensPartialSum(a0);
                default -> null;
            };
        }

        private static double gcd(long a, long b) {
            a = Math.abs(a); b = Math.abs(b);
            while (b != 0) { long t = b; b = a % b; a = t; }
            return a;
        }
    }
}
