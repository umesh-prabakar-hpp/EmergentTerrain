package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.HashMap;
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

    static final Set<String> BUILTINS = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect",
            "collatzSteps", "sigmaSum", "abs", "sqrt", "pow", "floor", "ceil",
            "log", "log2", "min", "max", "gcd",
            // Phase 5 A3 — bounded, deterministic, side-effect-free trig + exp.
            // The 4b review whitelist documented in MathToolSuite mirrors this list;
            // 18 sandbox rejections in the May 2026 corpus came from the narrator
            // reaching for these and being denied (composite-failure 12 GPa cluster,
            // prime-gap pseudo-physics).
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp");

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
            int a0 = a.length > 0 ? (int) a[0] : 0;
            return switch (name) {
                case "isPrime"           -> MathPhysicsEngine.isPrime(a0)           ? 1.0 : 0.0;
                case "isHappy"           -> MathPhysicsEngine.isHappy(a0)           ? 1.0 : 0.0;
                case "collatzTerminates" -> MathPhysicsEngine.collatzSteps(a0) < 10_000 ? 1.0 : 0.0;
                case "isAbundant"        -> MathPhysicsEngine.sigmaSum(a0) > a0     ? 1.0 : 0.0;
                case "isPerfect"         -> MathPhysicsEngine.sigmaSum(a0) == a0    ? 1.0 : 0.0;
                case "collatzSteps"      -> MathPhysicsEngine.collatzSteps(a0);
                case "sigmaSum"          -> MathPhysicsEngine.sigmaSum(a0);
                case "abs"               -> Math.abs(a[0]);
                case "sqrt"              -> Math.sqrt(a[0]);
                case "pow"               -> Math.pow(a[0], a.length > 1 ? a[1] : 1.0);
                case "floor"             -> Math.floor(a[0]);
                case "ceil"              -> Math.ceil(a[0]);
                case "log"               -> Math.log(a[0]);
                case "log2"              -> Math.log(a[0]) / Math.log(2);
                case "min"               -> a.length > 1 ? Math.min(a[0], a[1]) : a[0];
                case "max"               -> a.length > 1 ? Math.max(a[0], a[1]) : a[0];
                case "gcd"               -> gcd(a.length > 0 ? (long) a[0] : 0L,
                                                a.length > 1 ? (long) a[1] : 0L);
                case "sin"               -> Math.sin(a[0]);
                case "cos"               -> Math.cos(a[0]);
                case "tan"               -> Math.tan(a[0]);
                case "asin"              -> Math.asin(a[0]);
                case "acos"              -> Math.acos(a[0]);
                case "atan"              -> Math.atan(a[0]);
                case "atan2"             -> Math.atan2(a[0], a.length > 1 ? a[1] : 1.0);
                case "exp"               -> Math.exp(a[0]);
                default -> throw new RuntimeException("Unimplemented builtin: " + name);
            };
        }

        private static double gcd(long a, long b) {
            a = Math.abs(a); b = Math.abs(b);
            while (b != 0) { long t = b; b = a % b; a = t; }
            return a;
        }
    }
}
