package com.emergentterrain.llm;

import java.util.List;

/**
 * Phase 22 §22.3 — JVM-side mirror of the Julia sidecar's safety
 * filter ({@code julia-math-sidecar/src/safety.jl}). Rejects
 * expressions containing tokens that would let arbitrary code escape
 * the math evaluator before the HTTP request even leaves the JVM.
 *
 * <h2>Why mirror</h2>
 *
 * The sidecar already has a substring filter; this class adds it
 * upstream for three reasons:
 *
 * <ol>
 *   <li><b>One fewer round-trip on malicious input.</b> No reason to
 *       pay HTTP serialise + network + sidecar parse cost on input we
 *       can reject locally.</li>
 *   <li><b>Defense in depth.</b> If a future deployment somehow runs
 *       a compromised sidecar (image tag confusion, registry attack,
 *       supply chain), the JVM filter catches the LLM's input before
 *       it reaches the compromised process. Belt-and-braces against
 *       a class of attack the sidecar's filter can't see.</li>
 *   <li><b>Clearer audit trail.</b> A {@code client_safety_rejected}
 *       JuliaMathException carries the offending pattern in its
 *       message — the operator sees the exact input that triggered
 *       the reject in the JVM logs, without having to grep two
 *       processes' logs.</li>
 * </ol>
 *
 * <h2>Kept in sync</h2>
 *
 * The pattern list MUST stay equivalent to the Julia side's
 * {@code FORBIDDEN_PATTERNS}. When the Julia list grows, mirror the
 * addition here in the same commit. Conversely, when this list grows
 * (e.g. a corpus regression exposes a new bypass), also extend the
 * sidecar filter.
 *
 * <p>Stateless and package-private — only {@link JuliaMathClient}
 * should reach for it.
 */
final class JuliaSafetyFilter {

    /**
     * Forbidden substrings mirrored from
     * {@code julia-math-sidecar/src/safety.jl}. Plain substring
     * matching, NOT regex — same conservative defense as the sidecar.
     * Each comment line tracks WHY the pattern is unsafe; an
     * operator reading the audit log shouldn't have to consult Julia
     * docs to understand the reject.
     */
    static final List<String> FORBIDDEN_PATTERNS = List.of(
            // Package management — adding/updating packages mutates
            // host environment and pulls arbitrary code from the registry.
            "using Pkg",
            "import Pkg",
            "Pkg.add",
            "Pkg.update",
            "Pkg.develop",
            "Pkg.activate",
            "Pkg.instantiate",
            // Shell execution — backticks build a Cmd, run() invokes it.
            "run(",
            "Base.run",
            "`",
            // File system / network I/O.
            "open(",
            "read(",
            "write(",
            "download(",
            "Base.download",
            "Downloads.",
            "touch(",
            "rm(",
            "mkdir(",
            "mkpath(",
            "mv(",
            "cp(",
            "chmod(",
            "symlink(",
            // Process / environment control.
            "exit(",
            "Base.exit",
            "kill(",
            "Sys.",
            "ENV[",
            "withenv(",
            // Native code / FFI — bypasses everything else.
            "ccall(",
            "@ccall",
            "unsafe_",
            "Libdl.",
            "Libc.",
            // Module loading — include("/etc/passwd") would read+exec.
            "include(",
            "Base.include",
            "Base.evalfile",
            // Direct evaluator access — defeats substring filters.
            "eval(",
            "@eval",
            "Core.eval",
            "Meta.eval",
            "Meta.parse",
            "macroexpand",
            // Reflection exposing internal state.
            "InteractiveUtils.",
            "Base.invokelatest",  // server-side use only
            // IO redirection.
            "redirect_",
            "STDOUT",
            "STDERR"
    );

    private JuliaSafetyFilter() {}

    /**
     * Scan {@code expr} for any forbidden pattern. Returns null when
     * safe; otherwise the offending substring (caller throws with
     * that token in the message).
     *
     * <p>Null input is treated as safe — callers should null-check
     * before reaching this filter; an empty/null expression will be
     * rejected by the sidecar with a {@code bad_request} which is
     * the correct error code.
     */
    static String firstForbiddenPattern(String expr) {
        if (expr == null || expr.isEmpty()) return null;
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (expr.contains(pattern)) return pattern;
        }
        return null;
    }
}
