package com.emergentterrain.llm;

import java.util.List;

/**
 * Phase 25 §25.2 — JVM-side mirror of the sidecar's SymPy safety
 * filter ({@code julia-math-sidecar/src/handlers.jl}
 * {@code SYMPY_FORBIDDEN}). Rejects Python tokens that could let
 * arbitrary code escape SymPy's {@code sympify} parser before the
 * HTTP request leaves the JVM.
 *
 * <p>Sibling to {@link JuliaSafetyFilter} — same defense-in-depth
 * pattern, different syntax space. JuliaSafetyFilter covers Julia
 * code; this covers Python/SymPy.
 *
 * <h2>Why a separate filter</h2>
 *
 * The {@code /prove_identity} endpoint accepts Python-syntax strings
 * (parsed by SymPy's {@code sympify}). Julia-specific patterns like
 * {@code ccall(} or {@code using Pkg} are syntactically meaningless
 * for that endpoint and would never appear in a legitimate request;
 * Python-specific patterns like {@code __} (dunders) or
 * {@code subprocess.} are the relevant attack surface here. Sharing
 * one filter list would flag legitimate symbolic expressions (a
 * sympy expression like {@code sum(k, (k,1,n))} could be falsely
 * rejected for matching a Julia-side pattern).
 *
 * <h2>Kept in sync</h2>
 *
 * The pattern list MUST stay equivalent to the sidecar's
 * {@code SYMPY_FORBIDDEN} in {@code handlers.jl}. When the sidecar
 * list grows, mirror the addition here in the same commit.
 *
 * <p>Stateless and package-private — only {@link SympyIdentityClient}
 * should reach for it.
 */
final class SympyInputFilter {

    /**
     * Forbidden substrings mirrored from
     * {@code julia-math-sidecar/src/handlers.jl}'s
     * {@code SYMPY_FORBIDDEN}. Plain substring matching (not regex),
     * same conservative defense as the sidecar.
     */
    static final List<String> FORBIDDEN_PATTERNS = List.of(
            // Python dunders — attribute access into __class__,
            // __subclasses__, __builtins__, etc. is the standard
            // sympify-escape path documented across SymPy CVE history.
            "__",
            // Module references that would let an escaped expression
            // reach the OS, network, or interpreter internals.
            "os.",
            "subprocess.",
            "sys.",
            // Builtin functions that bypass the parser's safety.
            "open(",
            "eval(",
            "exec(",
            "compile(",
            // Module loading — defeats sympify's parser.
            "import ",
            // Anonymous function — sympify rejects these but a layered
            // filter catches future parser changes.
            "lambda",
            // Backticks — historically a Python 2 repr escape; harmless
            // in Python 3 syntax but cheap to filter as defense-in-depth.
            "`"
    );

    /**
     * Caller-supplied input size cap. Mirrors the sidecar's
     * {@code SYMPY_PROVE_MAX_EXPR_LEN}. A pathologically-long input
     * is rejected before any regex / sympify cost.
     */
    static final int MAX_EXPR_LEN = 512;

    private SympyInputFilter() {}

    /**
     * Scan {@code expr} for any forbidden pattern or oversize input.
     * Returns null when safe; otherwise a human-readable reason
     * (which the caller weaves into the exception message).
     *
     * <p>Null and empty input are treated as safe — callers null-check
     * before reaching this filter; empty/null reaches the sidecar
     * which rejects with {@code bad_request}.
     */
    static String firstViolation(String expr) {
        if (expr == null || expr.isEmpty()) return null;
        if (expr.length() > MAX_EXPR_LEN) {
            return "exceeds " + MAX_EXPR_LEN + "-char cap (got " + expr.length() + ")";
        }
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (expr.contains(pattern)) {
                return "contains forbidden pattern '" + pattern + "'";
            }
        }
        return null;
    }
}
