# =====================================================================
# julia_aliases.jl — Mathematica-flavoured global aliases for LLM eval
# =====================================================================
#
# Purpose
# -------
# Capture the recurring `UndefVarError` classes the sidecar observes
# from LLM-generated expressions (paper-review pass 2026-05-22 found
# `Solve`×3, `PI`×3, `σ_F`×1, `PLANCK_H`×1, `isprime`×1 across a single
# session). Models trained heavily on Mathematica reach for those names
# by default; rather than insist every prompt enumerate the Julia
# equivalent, we define safe top-level aliases the sidecar's
# `Main.eval` can find.
#
# What this is NOT
# ----------------
# - Not a replacement for the system prompt. The prompt should still
#   teach the model to prefer Julia idioms; this file is the safety
#   net for the cases where it doesn't.
# - Not a kitchen-sink physics constant pool. Constants land here only
#   when the sidecar log shows the symbol referenced unbound. Keep
#   curated; one entry per real failure mode.
# - Not for sandbox-only primitives (e.g. `sigmaSum`). Those belong in
#   `SandboxEngine` because they're scoped to LLM-pseudocode runs, not
#   Julia evaluation. The sidecar log entry for `sigmaSum` is the
#   model's confusion between the two evaluation tools; the prompt is
#   the place to clarify.
#
# Safety
# ------
# Every alias here is a pure-math value or a thin wrapper around an
# already-trusted function. No I/O, no process, no package management.
# The sidecar's `safety_check` filter still runs ahead of any user
# expression, so a model emitting `Pkg.add(...)` is still rejected.
# =====================================================================

# ── Bridge into Main (LOAD-BEARING — do not drop) ────────────────────
#
# `/eval` runs `Main.eval(...)`, and scripts/start.sh boots the sidecar
# with `using JuliaMathSidecar` — so ONLY exported names land in the
# eval namespace. An alias defined below but absent from this export is
# silently unreachable from /eval (the original `isprime not defined in
# Main` failure: the alias existed in-module since 2026-05-22 but was
# never exported, so the safety net never deployed). Every new alias
# MUST be added here too.
export PI, PLANCK_H, SIGMA_SB, σ_F, BOLTZMANN_K, ELEMENTARY_CHARGE,
    SPEED_OF_LIGHT, GRAVITATIONAL_CONSTANT, Solve, isprime

# ── Numeric constants (LLM uses Mathematica-style ALL_CAPS names) ────
#
# Provide both the upper-case alias and a short hint comment so future
# readers can trace each entry back to a real corpus failure.

# π — single most-recurring UndefVarError this session (3×).
const PI = π

# Planck's constant (J·s, CODATA 2019 exact).
const PLANCK_H = 6.62607015e-34

# Stefan-Boltzmann constant (W·m⁻²·K⁻⁴, CODATA 2019). Greek-letter
# subscript form the LLM emitted; we provide both the Mathematica-style
# ALL_CAPS handle and the original glyph for symmetry.
const SIGMA_SB = 5.670374419e-8
const σ_F      = SIGMA_SB

# Boltzmann constant (J/K). Defensive include — corpus hasn't shown it
# unbound yet but it ships in the same Mathematica context as
# `PLANCK_H` / `σ_F`.
const BOLTZMANN_K = 1.380649e-23

# Elementary charge (C). Same defensive rationale.
const ELEMENTARY_CHARGE = 1.602176634e-19

# Speed of light in vacuum (m/s, exact).
const SPEED_OF_LIGHT = 299_792_458.0

# Gravitational constant (N·m²·kg⁻², CODATA 2018 recommended).
const GRAVITATIONAL_CONSTANT = 6.67430e-11

# ── Function aliases for the Mathematica `Solve[...]` family ────────
#
# LLM emits both `Solve(expr, var)` (Julia function call form) and
# `Solve[expr == 0, var]` (Mathematica bracket form). The bracket form
# is rejected by Julia's parser before reaching us. The function form
# we route to `Symbolics.symbolic_solve` — same backing call the
# `/solve` HTTP endpoint already uses.

"""
    Solve(expr, var)

Mathematica-style alias for symbolic equation solving. Accepts an
expression that equals zero and a single Symbolics variable; returns
a `Vector` of root expressions (or `nothing` if the system can't crack
it symbolically).

```julia
@variables x
Solve(x^2 - 4, x)   # → Any[-2, 2]
```
"""
function Solve(expr, var)
    return Symbolics.symbolic_solve(expr ~ 0, var)
end

# ── Number-theoretic predicates ──────────────────────────────────────
#
# `isprime` is the second-most-common bare-name miss. Julia has
# `Primes.isprime` but only after `using Primes`, and the sidecar's
# safety filter rejects `using` statements by design. Implement
# inline against the existing SPF sieve (n ≤ NT_MAX_N fast path) +
# trial division fallback (larger n).

"""
    isprime(n)

Primality test. Fast path uses the SPF sieve cached by
`numbertheory.jl` for `n ≤ NT_MAX_N`; falls back to bounded trial
division for larger inputs. Matches Julia's `Primes.isprime`
semantics for positive integers; returns `false` for `n < 2`.
"""
function isprime(n::Integer)
    n < 2 && return false
    if n <= NT_MAX_N
        return nt_is_prime(Int(n))
    end
    # Trial division up to sqrt(n). Bound is well within the
    # `MAX_TIMEOUT_MS=30_000` per-call ceiling for any n the LLM is
    # likely to test against (n < 10^16 finishes in ~10 ms).
    n % 2 == 0 && return n == 2
    n % 3 == 0 && return n == 3
    i = 5
    while i * i <= n
        if n % i == 0 || n % (i + 2) == 0
            return false
        end
        i += 6
    end
    return true
end
