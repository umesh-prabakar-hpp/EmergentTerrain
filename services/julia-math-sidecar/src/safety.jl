# =====================================================================
# safety.jl — code safety filter
# =====================================================================
#
# Julia is Turing-complete and `Meta.parse` + `Core.eval` give full
# access to the runtime. An LLM that emits `\`rm -rf /\`` or
# `using Pkg; Pkg.add("…")` would otherwise be executed as-is.
#
# Two layers of defense:
#
# 1. **Substring rejection (this file).** Cheap, transparent, and
#    catches the obvious attacks. The list of forbidden patterns is
#    written below — kept short and readable so a reader can audit it.
#
# 2. **Process isolation (deployment concern, NOT this file).** The
#    sidecar should run in a systemd unit with `ReadOnlyPaths=`,
#    `NoNewPrivileges=true`, `RestrictAddressFamilies=AF_UNIX AF_INET`,
#    `PrivateNetwork=true`, and memory/CPU limits. Or in a Docker
#    container with `--read-only --network=none`. The substring filter
#    is necessary but not sufficient — assume it has gaps and rely on
#    OS-level confinement for the real guarantee.
#
# Reject heuristics
# -----------------
# These patterns are matched as plain substrings against the incoming
# expression text. Substring matching is intentionally conservative —
# false positives are far cheaper than false negatives. A user who
# legitimately wants to compute `Pkg.SemVer` for some reason can
# rephrase; we don't.
# =====================================================================

# Forbidden substrings. Each line documents why the pattern is unsafe.
const FORBIDDEN_PATTERNS = [
    # Package management — adding/updating packages mutates the host
    # environment and can pull arbitrary code from the registry.
    "using Pkg",
    "import Pkg",
    "Pkg.add",
    "Pkg.update",
    "Pkg.develop",
    "Pkg.activate",
    "Pkg.instantiate",
    # Shell execution. Backticks construct a `Cmd` literal; `run(...)`
    # or `read(\`...\`, …)` executes it.
    "run(",
    "Base.run",
    "`",                   # backtick command literal
    # File system / network I/O.
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
    # Process / environment control.
    "exit(",
    "Base.exit",
    "kill(",
    "Sys.",
    "ENV[",
    "withenv(",
    # Native code / FFI — bypasses everything above.
    "ccall(",
    "@ccall",
    "unsafe_",
    "Libdl.",
    "Libc.",
    # Module loading — `include(\"/etc/passwd\")` would read+exec.
    "include(",
    "Base.include",
    "Base.evalfile",
    # Direct evaluator access — defeats the substring filter via
    # constructed strings.
    "eval(",
    "@eval",
    "Core.eval",
    "Meta.eval",
    "Meta.parse",
    "macroexpand",
    # Reflection that exposes internal state.
    "InteractiveUtils.",
    "Base.invokelatest",
    # IO redirection — could fill disk or capture other handlers.
    "redirect_",
    "STDOUT",
    "STDERR",
]

# Result type returned by `safety_check`. Mirrors the Reject/Ok pattern
# from EmergentTerrain's PremiseValidator — a Reject carries enough
# info for the HTTP handler to compose a useful error response.
struct SafetyOk end
struct SafetyReject
    pattern::String
    explanation::String
end

const SAFETY_OK = SafetyOk()

"""
    safety_check(expr::AbstractString) -> SafetyOk | SafetyReject

Scan `expr` for any forbidden pattern. Returns `SAFETY_OK` if none
matches; otherwise a `SafetyReject` naming the offending pattern.

The check is plain substring matching (no regex compilation, no AST
walk). This is deliberately the cheapest possible defense — the
authoritative defense is OS-level confinement of the running process.

Order of patterns is "obvious first" so the most common forbidden
patterns (package management, shell exec) fail fast.
"""
function safety_check(expr::AbstractString)
    for pattern in FORBIDDEN_PATTERNS
        if occursin(pattern, expr)
            return SafetyReject(
                pattern,
                "Expression contains forbidden token '$pattern'. " *
                "This sidecar evaluates math expressions only — " *
                "I/O, package management, process control, and " *
                "module loading are disallowed."
            )
        end
    end
    return SAFETY_OK
end

# Convenience for handlers: short-circuit evaluation with a rejection
# response when the check fails. Returns nothing on OK, an HTTP.Response
# on reject (callers should `return` immediately after).
function safety_reject_response(expr::AbstractString)
    result = safety_check(expr)
    if result isa SafetyReject
        body = JSON3.write(Dict(
            "ok" => false,
            "error" => "safety_rejected",
            "pattern" => result.pattern,
            "explanation" => result.explanation,
        ))
        return HTTP.Response(400, ["Content-Type" => "application/json"], body)
    end
    return nothing
end
