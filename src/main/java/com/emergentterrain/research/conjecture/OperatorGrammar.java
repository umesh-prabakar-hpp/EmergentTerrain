package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase 30 Wave 5 — 30.5 P1: the closed operator grammar for vocabulary expansion (candidate
 * generation only; admission is P2's MDL gate). See {@code vocabulary-expansion-mdl.md} for the
 * settled design (decisions G/M/I, 2026-06-03).
 *
 * <p>A <b>minimal multiplicative</b> grammar over the existing scalar vocabulary: unary
 * {@code {log, sqrt, square, reciprocal}} and binary {@code {*, /}} (no {@code +}/{@code −} — the
 * engine's affine {@code k,c} already cover those; no constants — same reason). Closed under
 * composition up to a depth bound (G2: depth 1 is the primary use; depth is a parameter). Each
 * candidate carries a canonical {@link Expr#render() rendering} (used for dedup) and a guarded
 * {@link Expr#eval evaluator} (domain failures → {@code NaN}).
 *
 * <p>Structural pruning at generation time (no data needed — numeric near-identity to an existing
 * series is P2/admission):
 * <ul>
 *   <li>canonical-form dedup (commutative {@code *} ordered by render, so {@code a*b ≡ b*a});</li>
 *   <li>drop {@code x∘x} degeneracies ({@code x*x} duplicates {@code square(x)}, {@code x/x}=1);</li>
 *   <li><b>dimension guard</b> — reject any expression whose leaves mix an O(1)-bounded
 *       number-theoretic scalar with a harness window-magnitude (the "meters × seconds" category
 *       error), reusing {@link ConjectureUnitConsistencyFilter}'s class registry.</li>
 * </ul>
 *
 * <p>Pure, stateless, deterministic. {@code log} is natural log (the downstream MDL test is
 * Spearman-based, so the log base is immaterial).
 */
public final class OperatorGrammar {

    private static final double EPS = 1e-12;

    public enum Unary { LOG, SQRT, SQUARE, RECIPROCAL }

    public enum Binary {
        MUL("*", true), DIV("/", false);
        final String sym;
        final boolean commutative;
        Binary(String sym, boolean commutative) { this.sym = sym; this.commutative = commutative; }
    }

    private OperatorGrammar() {
    }

    /** A grammar expression over base scalars. Immutable; equality by canonical {@link #render()}. */
    public static final class Expr {
        private enum Kind { LEAF, UNARY, BINARY }
        private final Kind kind;
        private final String name;     // LEAF
        private final Unary uop;       // UNARY
        private final Binary bop;      // BINARY
        private final Expr a;
        private final Expr b;
        private final String render;
        private final int depth;
        private final Set<String> operands;

        private Expr(Kind kind, String name, Unary uop, Binary bop, Expr a, Expr b) {
            this.kind = kind;
            this.name = name;
            this.uop = uop;
            this.bop = bop;
            this.a = a;
            this.b = b;
            this.render = computeRender();
            this.depth = kind == Kind.LEAF ? 0
                    : kind == Kind.UNARY ? 1 + a.depth
                    : 1 + Math.max(a.depth, b.depth);
            Set<String> ops = new TreeSet<>();
            if (kind == Kind.LEAF) {
                ops.add(name);
            } else if (kind == Kind.UNARY) {
                ops.addAll(a.operands);
            } else {
                ops.addAll(a.operands);
                ops.addAll(b.operands);
            }
            this.operands = Set.copyOf(ops);
        }

        public static Expr leaf(String name) {
            return new Expr(Kind.LEAF, name, null, null, null, null);
        }

        public static Expr unary(Unary op, Expr arg) {
            return new Expr(Kind.UNARY, null, op, null, arg, null);
        }

        public static Expr binary(Binary op, Expr left, Expr right) {
            return new Expr(Kind.BINARY, null, null, op, left, right);
        }

        private String computeRender() {
            return switch (kind) {
                case LEAF -> name;
                case UNARY -> uop.name().toLowerCase() + "(" + a.render() + ")";
                case BINARY -> {
                    String l = a.render();
                    String r = b.render();
                    if (bop.commutative && l.compareTo(r) > 0) {   // canonical order for a*b ≡ b*a
                        String t = l; l = r; r = t;
                    }
                    yield "(" + l + " " + bop.sym + " " + r + ")";
                }
            };
        }

        public String render() { return render; }

        public int depth() { return depth; }

        /** Total node count (operators + operands) — the basis for the MDL description length. */
        public int nodeCount() {
            return switch (kind) {
                case LEAF -> 1;
                case UNARY -> 1 + a.nodeCount();
                case BINARY -> 1 + a.nodeCount() + b.nodeCount();
            };
        }

        /** Base scalar names used. */
        public Set<String> operands() { return operands; }

        /** Evaluate over a sample's scalar map; domain failures (log≤0, sqrt<0, /0) → NaN. */
        public double eval(Map<String, Double> s) {
            switch (kind) {
                case LEAF -> {
                    Double v = s.get(name);
                    return (v == null || !Double.isFinite(v)) ? Double.NaN : v;
                }
                case UNARY -> {
                    double x = a.eval(s);
                    if (!Double.isFinite(x)) {
                        return Double.NaN;
                    }
                    return switch (uop) {
                        case LOG -> x > 0 ? Math.log(x) : Double.NaN;
                        case SQRT -> x >= 0 ? Math.sqrt(x) : Double.NaN;
                        case SQUARE -> x * x;
                        case RECIPROCAL -> Math.abs(x) > EPS ? 1.0 / x : Double.NaN;
                    };
                }
                default -> {
                    double x = a.eval(s);
                    double y = b.eval(s);
                    if (!Double.isFinite(x) || !Double.isFinite(y)) {
                        return Double.NaN;
                    }
                    return switch (bop) {
                        case MUL -> x * y;
                        case DIV -> Math.abs(y) > EPS ? x / y : Double.NaN;
                    };
                }
            }
        }

        @Override public String toString() { return render; }
    }

    /**
     * Enumerate candidate expressions of operator-depth {@code 1..maxDepth} over {@code baseScalars},
     * deduped by canonical render, degeneracy-pruned, and dimension-guarded. Deterministic (input
     * order). Base scalars themselves (depth 0) are NOT returned — they already exist.
     */
    public static List<Expr> enumerate(List<String> baseScalars, int maxDepth) {
        List<Expr> all = new ArrayList<>();
        if (baseScalars == null || baseScalars.isEmpty()) {
            return all;
        }
        int d = Math.max(1, maxDepth);
        Set<String> seen = new LinkedHashSet<>();
        Map<Integer, List<Expr>> byDepth = new LinkedHashMap<>();
        List<Expr> leaves = new ArrayList<>();
        for (String s : baseScalars) {
            Expr leaf = Expr.leaf(s);
            leaves.add(leaf);
            seen.add(leaf.render());   // base scalars are not re-emitted
        }
        byDepth.put(0, leaves);

        for (int level = 1; level <= d; level++) {
            List<Expr> prev = byDepth.get(level - 1);
            List<Expr> lower = new ArrayList<>();
            for (int k = 0; k < level; k++) {
                lower.addAll(byDepth.getOrDefault(k, List.of()));
            }
            List<Expr> cur = new ArrayList<>();
            for (Unary op : Unary.values()) {
                for (Expr x : prev) {
                    tryAdd(Expr.unary(op, x), seen, cur);
                }
            }
            for (Expr x : prev) {
                for (Expr y : lower) {
                    if (x.render().equals(y.render())) {
                        continue;   // x∘x: x*x duplicates square(x); x/x = 1
                    }
                    tryAdd(Expr.binary(Binary.MUL, x, y), seen, cur);
                    tryAdd(Expr.binary(Binary.DIV, x, y), seen, cur);
                    tryAdd(Expr.binary(Binary.DIV, y, x), seen, cur);
                }
            }
            byDepth.put(level, cur);
            all.addAll(cur);
        }
        return all;
    }

    /**
     * Parse a canonical render back into an {@link Expr} — the inverse of {@link Expr#render()}.
     * Grammar: {@code leaf | unary "(" expr ")" | "(" expr " " op " " expr ")"}. Used to ingest the
     * 30.7 mechanism DSL. Throws {@link IllegalArgumentException} on malformed input.
     */
    public static Expr parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null expression");
        }
        String t = s.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        if (t.charAt(0) == '(') {
            String inner = stripOuterParens(t);
            int opIdx = topLevelBinaryOp(inner);
            if (opIdx < 0) {
                throw new IllegalArgumentException("no top-level binary operator: " + s);
            }
            Binary op = inner.charAt(opIdx) == '*' ? Binary.MUL : Binary.DIV;
            Expr left = parse(inner.substring(0, opIdx).trim());
            Expr right = parse(inner.substring(opIdx + 1).trim());
            return Expr.binary(op, left, right);
        }
        int paren = t.indexOf('(');
        if (paren < 0) {
            return Expr.leaf(t);   // identifier
        }
        if (t.charAt(t.length() - 1) != ')') {
            throw new IllegalArgumentException("malformed unary: " + s);
        }
        Unary uop = Unary.valueOf(t.substring(0, paren).trim().toUpperCase());
        return Expr.unary(uop, parse(t.substring(paren + 1, t.length() - 1)));
    }

    private static String stripOuterParens(String t) {
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '(') {
                depth++;
            } else if (t.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    if (i != t.length() - 1) {
                        throw new IllegalArgumentException("unbalanced parens: " + t);
                    }
                    return t.substring(1, i);
                }
            }
        }
        throw new IllegalArgumentException("unbalanced parens: " + t);
    }

    /** Index of the single depth-0 '*' or '/' operator char in a stripped binary body, or -1. */
    private static int topLevelBinaryOp(String inner) {
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == '*' || c == '/')) {
                return i;
            }
        }
        return -1;
    }

    private static void tryAdd(Expr e, Set<String> seen, List<Expr> cur) {
        if (mixesIncompatibleDimensions(e) || !seen.add(e.render())) {
            return;
        }
        cur.add(e);
    }

    /** Reject expressions whose leaves mix an O(1)-bounded NT scalar with a harness window-magnitude. */
    static boolean mixesIncompatibleDimensions(Expr e) {
        boolean bounded = false;
        boolean window = false;
        for (String op : e.operands()) {
            if (ConjectureUnitConsistencyFilter.isBoundedNumberTheoretic(op)) {
                bounded = true;
            }
            if (ConjectureUnitConsistencyFilter.isWindowMagnitude(op)) {
                window = true;
            }
        }
        return bounded && window;
    }
}
