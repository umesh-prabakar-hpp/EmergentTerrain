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

# An identifier char (Julia identifiers: letters incl. unicode like π, digits,
# underscore).
_ident_char(c::Char) = isletter(c) || isdigit(c) || c == '_'

# True if `pattern` occurs in `expr` at a LEFT identifier boundary. Patterns
# that begin with an identifier char (e.g. "rm(", "read(", "Pkg.add") must not
# be the tail of a longer identifier — so "norm(" doesn't trip "rm(" and
# "spread(" doesn't trip "read(". Patterns starting with a non-identifier char
# (backtick, "@ccall", …) are matched plainly: they can't be embedded in an
# identifier. Still pure string scanning — no regex, no AST walk.
function _forbidden_match(expr::AbstractString, pattern::AbstractString)
    isempty(pattern) && return false
    needs_boundary = _ident_char(first(pattern))
    pos = firstindex(expr)
    while true
        r = findnext(pattern, expr, pos)
        r === nothing && return false
        needs_boundary || return true
        s = first(r)
        if s == firstindex(expr) || !_ident_char(expr[prevind(expr, s)])
            return true   # token starts at a left boundary → a real match
        end
        pos = nextind(expr, s)   # false hit inside a longer identifier; keep scanning
    end
end

"""
    safety_check(expr::AbstractString) -> SafetyOk | SafetyReject

Scan `expr` for any forbidden pattern. Returns `SAFETY_OK` if none
matches; otherwise a `SafetyReject` naming the offending pattern.

The check is identifier-boundary-aware string scanning (no regex, no AST
walk): a forbidden call-token like `rm(` matches only at the start of an
identifier, so legitimate math such as `norm(` / `spread(` is NOT rejected
while a standalone `rm(` / `read(` still is. This is deliberately a cheap
first layer — the authoritative defense is OS-level confinement.

Order of patterns is "obvious first" so the most common forbidden
patterns (package management, shell exec) fail fast.
"""
function safety_check(expr::AbstractString)
    for pattern in FORBIDDEN_PATTERNS
        if _forbidden_match(expr, pattern)
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

# =====================================================================
# PySR operator allowlist (phase-24 §24.1)
# =====================================================================
#
# /pysr accepts a list of arithmetic operators that PySR's evolutionary
# search may compose. Unlike /eval — which takes free-form Julia text
# and needs substring rejection — /pysr never executes user-supplied
# strings; the operators are mapped to fixed Julia functions on the
# server side. So the only safety question is "did the caller name an
# operator we don't know how to map?" — which is a closed-vocabulary
# allowlist, not a denylist.
#
# Allowlist sized to common physics/materials laws: arithmetic, powers,
# exp/log, sqrt, abs, trig (sin/cos). Wider sets (tanh, erf, gamma) can
# be added when a specific paper demands it; phase-24 §"Open question 2"
# documents the policy.

const ALLOWED_PYSR_OPERATORS = Set([
    "+", "-", "*", "/", "^",
    "exp", "log", "sqrt", "abs", "sin", "cos",
])

"""
    pysr_operators_check(ops) -> SafetyOk | SafetyReject

Validate that every element of `ops` is a string in `ALLOWED_PYSR_OPERATORS`.
Returns `SAFETY_OK` if all are allowed; otherwise a `SafetyReject` naming
the first offender.
"""
function pysr_operators_check(ops)
    for op in ops
        if !(op isa AbstractString)
            return SafetyReject(string(op),
                "Operator entry is not a string: $(repr(op)).")
        end
        if !(String(op) in ALLOWED_PYSR_OPERATORS)
            allowed = join(sort(collect(ALLOWED_PYSR_OPERATORS)), ", ")
            return SafetyReject(String(op),
                "Operator '$op' is not in the PySR allowlist. " *
                "Allowed: $allowed.")
        end
    end
    return SAFETY_OK
end

# Convenience: build a 400 rejection response from a pysr_operators_check.
function pysr_operators_reject_response(ops)
    result = pysr_operators_check(ops)
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
