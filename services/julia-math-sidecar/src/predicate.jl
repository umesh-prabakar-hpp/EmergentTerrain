# =====================================================================
# src/predicate.jl — Phase 28 §28.5a — DynamicComputeEngine port
# =====================================================================
#
# Port of com.emergentterrain.research.DynamicComputeEngine for the
# /predicate/scan endpoint. Same predicate allowlist, same AND/OR
# parser, same fraction / mean / max metrics, same SE formula.
#
# Wire contract: the sidecar returns *only* the numeric scalars + the
# claim status. The JVM caller layers on the findings / equation /
# hypothesis prose (avoiding byte-identical-format friction between
# Java's String.format and Julia's @sprintf).

const PREDICATE_MAX_RANGE = 500  # mirrors DynamicComputeEngine.MAX_RANGE

# Predicate allowlist — every name must map to a pure Int → Bool.
# Adding a new predicate here means adding the matching `case` in
# JVM DynamicComputeEngine.parseSinglePredicate in the same commit;
# parity test catches divergence.
const PREDICATE_FNS = Dict{String, Function}(
    "isPrime"           => is_prime,
    "isHappy"           => is_happy,
    "collatzTerminates" => n -> collatz_steps(n) < 10_000,
    "isAbundant"        => is_abundant,
    "isPerfect"         => is_perfect,
)

const VALUE_FNS = Dict{String, Function}(
    "collatzSteps" => collatz_steps,
    "sigmaSum"     => sigma_sum,
)

"""
    parse_predicate(spec) -> Union{Function, Nothing}

Build a composed predicate from a string like
"isPrime AND isHappy" or "isAbundant OR isPerfect". Returns
`nothing` when any token is unknown — same surface as JVM
DynamicComputeEngine.parsePredicate.

Order of operator detection matters: AND is checked before OR
(JVM does the same). Mixed AND/OR in one spec is not supported
(matches JVM); only one operator level.
"""
function parse_predicate(spec::AbstractString)
    isempty(strip(spec)) && return nothing
    if occursin(" AND ", spec)
        parts = split(spec, " AND ")
        first_p = parse_single_predicate(strip(parts[1]))
        first_p === nothing && return nothing
        out = first_p
        for i in 2:length(parts)
            p = parse_single_predicate(strip(parts[i]))
            p === nothing && return nothing
            prev = out
            out = n -> prev(n) && p(n)
        end
        return out
    end
    if occursin(" OR ", spec)
        parts = split(spec, " OR ")
        first_p = parse_single_predicate(strip(parts[1]))
        first_p === nothing && return nothing
        out = first_p
        for i in 2:length(parts)
            p = parse_single_predicate(strip(parts[i]))
            p === nothing && return nothing
            prev = out
            out = n -> prev(n) || p(n)
        end
        return out
    end
    return parse_single_predicate(strip(spec))
end

function parse_single_predicate(name::AbstractString)
    return get(PREDICATE_FNS, String(name), nothing)
end

function parse_value_fn(name::AbstractString)
    return get(VALUE_FNS, String(name), nothing)
end

"""
    classify_status(deviation, baseline; partial_threshold, refuted_threshold) -> String

ClaimStatus selector. Returns "SUPPORTED" / "PARTIAL" / "REFUTED" —
JVM ClaimStatus enum names verbatim so the wire round-trip stays
clean.
"""
function classify_status(deviation::Float64, baseline::Float64;
                          supported_threshold::Float64,
                          partial_threshold::Float64)
    if deviation <= supported_threshold
        return "SUPPORTED"
    elseif deviation <= partial_threshold
        return "PARTIAL"
    else
        return "REFUTED"
    end
end

"""
    predicate_scan_fraction(start, end_, n, pred, baseline) -> Dict

Computes the fraction of integers in [start, end_) that satisfy the
predicate. Returns the numeric outputs only (no prose); JVM owns
the findings/hypothesis/equation strings. SE formula and the
SUPPORTED/PARTIAL/REFUTED bands match JVM
DynamicComputeEngine.computeFraction byte-for-byte.
"""
function predicate_scan_fraction(start::Int, end_::Int, n::Int, pred::Function,
                                  baseline::Float64)
    count = 0
    for i in start:(end_ - 1)
        if pred(i)
            count += 1
        end
    end
    fraction  = Float64(count) / n
    deviation = abs(fraction - baseline)
    se        = sqrt(max(0.0, fraction * (1.0 - fraction)) / n)
    ci        = 1.96 * se
    status = classify_status(deviation, baseline;
        supported_threshold = max(0.01, baseline * 0.15),
        partial_threshold   = max(0.03, baseline * 0.40))
    return Dict{String,Any}(
        "metric"      => "fraction_satisfying",
        "count"       => count,
        "n"           => n,
        "fraction"    => fraction,
        "deviation"   => deviation,
        "se"          => se,
        "ci"          => ci,
        "status"      => status,
    )
end

"""
    predicate_scan_value(start, end_, pred, val_fn, baseline, is_max) -> Dict

Computes the running mean (Welford) and max of `val_fn(i)` over
integers in [start, end_) that satisfy the predicate. Returns
numerics only. The Welford SE formula and SUPPORTED/PARTIAL/REFUTED
bands match JVM DynamicComputeEngine.computeValueMetric byte-for-byte
including the `baseline * 0.1 / 0.3` thresholds.
"""
function predicate_scan_value(start::Int, end_::Int, pred::Function,
                               val_fn::Function, baseline::Float64, is_max::Bool)
    w_mean = 0.0
    w_m2   = 0.0
    max_val = typemin(Int64)
    w_count = 0
    for i in start:(end_ - 1)
        pred(i) || continue
        val = Int64(val_fn(i))
        w_count += 1
        delta = Float64(val) - w_mean
        w_mean += delta / w_count
        w_m2 += delta * (Float64(val) - w_mean)
        if val > max_val
            max_val = val
        end
    end
    if w_count == 0
        return Dict{String,Any}("metric" => "empty", "n" => 0)
    end
    se = w_count > 1 ? sqrt(w_m2 / (w_count - 1) / w_count) : 0.0
    primary   = is_max ? Float64(max_val) : abs(w_mean - baseline)
    deviation = abs(w_mean - baseline)
    status = classify_status(deviation, baseline;
        supported_threshold = baseline * 0.1,
        partial_threshold   = baseline * 0.3)
    return Dict{String,Any}(
        "metric"     => is_max ? "max_of" : "mean_of",
        "count"      => w_count,
        "mean"       => w_mean,
        "se"         => se,
        "max"        => max_val == typemin(Int64) ? 0 : max_val,
        "primary"    => primary,
        "deviation"  => deviation,
        "baseline"   => baseline,
        "status"     => status,
    )
end
