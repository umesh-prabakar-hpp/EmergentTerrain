# =====================================================================
# src/math_invariants.jl — Phase 28 §28.4 — DERIVED scalars + extract
# =====================================================================
#
# Port of com.emergentterrain.research.conjecture.MathInvariants. Pure
# functions over the BASE map produced by `nt_evaluate_window` plus the
# window metadata (windowStart, windowSize). Output mirrors
# MathInvariants.extract: the same key ordering, the same NaN-vs-zero
# semantics, and the same vocabulary surface.
#
# When the JVM side bumps a DERIVED formula OR adds a new BASE scalar,
# bump the corresponding entry here in the same commit — the §28.4
# parity test (ConjectureVerifierParityTest) will catch any divergence
# on the next CI run.

const MATH_BASE_NAMES = String[
    "abundant_density",
    "deficient_density",
    "prime_density",
    "happy_density",
    "mean_omega",
    "mean_Omega",
    "mean_divisor_count",
    "max_divisor_count",
    "mertens_delta",
    "max_prime_gap",
    "twin_prime_density",
    "omega_ek_ratio",       # 2026-06-13 Erdős–Kac: Var(Ω)/ln ln x → 1
    "dirichlet_error",      # 2026-06-13 Dirichlet divisor error Δ(x)/x^{1/4}
    "squarefree_density",        # 2026-06-16 Stage 1: Ω==ω → 6/π² (validation object)
    "richly_divisible_density",  # 2026-06-16 Stage 1: d > ω² (uncharted)
    "omega_excess_density",      # 2026-06-16 Stage 1: Ω−ω ≥ 2 (uncharted)
]

"""
    math_invariants_extract(raw, window_start, window_size) -> Vector{Pair{String,Float64}}

Flatten one window evaluation into the conjecture-engine's named-
scalar map. Adds window metadata (_window_size, _window_start, _tick),
then BASE scalars (preserving order from raw), then DERIVED scalars
in the JVM's MathInvariants.DERIVED iteration order:

  abundant_minus_deficient = abundant_density - deficient_density
  omega_Omega_gap          = mean_Omega - mean_omega
  log_window_size          = log10(window_size) if > 0 else 0.0
  sqrt_window_size         = sqrt(window_size)
  log_window_start         = log10(window_start) if > 0 else 0.0
  mertens_per_sqrt_W       = mertens_delta / sqrt(window_size) if > 0 else 0.0

Returns a Vector{Pair{String,Float64}} (the same JSON3-serialises-
to-object-array shape /numbertheory/window uses) so JSON3 emits each
entry as `{"name": value}` and the JVM parser reads back in order.
"""
function math_invariants_extract(raw, window_start::Integer, window_size::Integer)::Vector{Pair{String,Float64}}
    flat = Vector{Pair{String,Float64}}()
    push!(flat, "_window_size"  => Float64(window_size))
    push!(flat, "_window_start" => Float64(window_start))
    push!(flat, "_tick"         => Float64(window_start))

    # Build a quick-lookup dict for the DERIVED formulas to read BASE
    # values without re-iterating the raw vector each time.
    raw_lookup = Dict{String,Float64}()
    for (k, v) in raw
        raw_lookup[String(k)] = Float64(v)
    end

    for name in MATH_BASE_NAMES
        v = get(raw_lookup, name, 0.0)
        push!(flat, name => v)
    end

    # DERIVED — same iteration order as the JVM LinkedHashMap.
    abundant   = get(raw_lookup, "abundant_density",   0.0)
    deficient  = get(raw_lookup, "deficient_density",  0.0)
    omega      = get(raw_lookup, "mean_omega",         0.0)
    big_omega  = get(raw_lookup, "mean_Omega",         0.0)
    mertens    = get(raw_lookup, "mertens_delta",      0.0)
    ws = Float64(window_size)
    wstart = Float64(window_start)

    push!(flat, "abundant_minus_deficient" => abundant - deficient)
    push!(flat, "omega_Omega_gap"          => big_omega - omega)
    push!(flat, "log_window_size"          => ws > 0.0 ? log10(ws) : 0.0)
    push!(flat, "sqrt_window_size"         => sqrt(ws))
    push!(flat, "log_window_start"         => wstart > 0.0 ? log10(wstart) : 0.0)
    # 2026-06-08 — growth anchors (JVM MathInvariants mirror): sqrt(window_start)
    # and ln²(window center) — natural log, the Cramér ln²x scale. Computed
    # IDENTICALLY to the JVM so a miner-seeded (k,c) matches the verifier here.
    push!(flat, "sqrt_window_start"        => sqrt(wstart))
    push!(flat, "log_sq_window_start"      => begin
        c = wstart + ws / 2.0
        c > 1.0 ? log(c)^2 : 0.0
    end)
    push!(flat, "mertens_per_sqrt_W"       => ws > 0.0 ? mertens / sqrt(ws) : 0.0)
    # Phase 30 §5.2 open-behavior derived scalars (2026-06-05), JVM-mirrored:
    # Cramér-normalized record gap (conjecturally O(1), open extremes) and the
    # Hardy–Littlewood twin ratio (→ 2C₂ ≈ 1.3203 if HL holds — a real constant
    # the miner can discover). Window center for the gap normalization.
    gap        = get(raw_lookup, "max_prime_gap",      0.0)
    twin       = get(raw_lookup, "twin_prime_density", 0.0)
    prime      = get(raw_lookup, "prime_density",      0.0)
    center = wstart + ws / 2.0
    lc = center > 1.0 ? log(center) : 0.0
    push!(flat, "cramer_gap_ratio" => lc > 0.0 ? gap / (lc * lc) : 0.0)
    push!(flat, "twin_vs_prime_sq" => prime > 0.0 ? twin / (prime * prime) : 0.0)
    # 2026-06-13 second-order (JVM MathInvariants mirror): mean_divisor_count − ln(center) → ≈ 2γ
    # (γ = Euler–Mascheroni), the constant after the textbook ~ln x divisor law. Natural log of the
    # window center, computed IDENTICALLY to the JVM so verify_constant matches the miner.
    mdc = get(raw_lookup, "mean_divisor_count", 0.0)
    push!(flat, "mean_divisor_excess" => center > 1.0 ? mdc - log(center) : 0.0)
    # 2026-06-14 (room b72c4e10 §1 rec #1) — Wigert growth anchor for max_divisor_count:
    # exp(ln(center)/ln ln(center)), the maximal-order scale of d(n). Unit coefficient dominates
    # d(n) asymptotically (true Wigert constant ln2 < 1) yet is ≈tight at reachable x. JVM
    # MathInvariants mirror — computed IDENTICALLY (same Math.E guard, same isfinite overflow guard)
    # so a miner-seeded (k,c) on wigert_bound matches the verifier. Pure geometry (window center).
    push!(flat, "wigert_bound" => begin
        if center <= ℯ
            0.0
        else
            lc = log(center); llc = log(lc); v = exp(lc / llc)
            isfinite(v) ? v : 0.0
        end
    end)
    # 2026-06-14 (room b72c4e10 §1 rec #1 redux) — ln ln x growth anchor for mean_omega/mean_Omega:
    # ln(ln(center)), the Hardy–Ramanujan/Erdős–Kac normal-order scale of ω(n)/Ω(n). JVM
    # MathInvariants mirror — IDENTICAL guards (center > 1, isfinite) so a miner-seeded (k,c) on
    # log_log_window_start matches the verifier. Pure geometry (window center, natural log).
    push!(flat, "log_log_window_start" => begin
        if center <= 1.0
            0.0
        else
            lc = log(center); llc = log(lc)
            isfinite(llc) ? llc : 0.0
        end
    end)

    return flat
end

"""
    math_invariants_vocabulary() -> Vector{String}

All conjecture-eligible scalar names (BASE + DERIVED), in the same
deterministic order JVM MathInvariants.vocabulary() emits. Used by
the verify handler to validate caller-supplied lhs/rhs before
dispatching to the engine.
"""
function math_invariants_vocabulary()::Vector{String}
    out = String[]
    append!(out, MATH_BASE_NAMES)
    push!(out, "abundant_minus_deficient")
    push!(out, "omega_Omega_gap")
    push!(out, "log_window_size")
    push!(out, "sqrt_window_size")
    push!(out, "log_window_start")
    push!(out, "sqrt_window_start")      # 2026-06-08 growth anchors (JVM mirror)
    push!(out, "log_sq_window_start")
    push!(out, "mertens_per_sqrt_W")
    # Phase 30 §5.2 (2026-06-06 review rec #3) — the two open-behavior derived
    # scalars were computed by math_invariants_flat above but missing here, so
    # /conjecture/verify rejected them with bad_request and the JVM fell back
    # to the per-window path (P-b72c-214's three verify calls).
    push!(out, "cramer_gap_ratio")
    push!(out, "twin_vs_prime_sq")
    push!(out, "mean_divisor_excess")    # 2026-06-13 second-order (JVM mirror): → ≈ 2γ
    push!(out, "wigert_bound")           # 2026-06-14 Wigert growth anchor (JVM mirror): exp(ln x/ln ln x)
    push!(out, "log_log_window_start")   # 2026-06-14 ln ln x growth anchor (JVM mirror): mean_omega/mean_Omega
    return out
end
