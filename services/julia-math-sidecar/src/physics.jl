# =====================================================================
# src/physics.jl — Phase 28 §28.5b — MathPhysicsEngine.compute port
# =====================================================================
#
# Vertical-slice port of one (domain, topicIdx) pair from JVM
# MathPhysicsEngine.compute to validate the /physics/topic wire
# contract. The full 9-domain × ~10-topic surface (~3,710 lines) is
# a multi-day port; this slice ports MATHEMATICS topic 0 (prime
# constellations) and leaves the rest as a documented backlog. JVM
# JuliaMathClient.physicsTopic returns Optional.empty() for not-yet-
# ported pairs so callers fall back to the in-process MathPhysicsEngine.
#
# Wire contract (matches §28.5a /predicate/scan): numeric scalars +
# ClaimStatus name only. The JVM caller composes the findings /
# hypothesis / equation prose locally — avoids byte-identical-format
# friction between Java String.format and Julia @sprintf.

# Hardy-Littlewood twin-prime constant, 2·C₂. Same digits the JVM
# uses; bump in lockstep.
const HARDY_LITTLEWOOD_TWIN_CONSTANT = 1.320323631693739

"""
    physics_prime_constellations_at(start, width) -> Dict

Inner per-window evaluation of MathPhysicsEngine.primeConstellationsAt.
Counts twin / cousin / sexy primes and max gap across primes in
[max(2, start), start + max(50, width)); returns the H-L twin
prediction, the deviation, and a ClaimStatus per the JVM threshold
bands (≤15% SUPPORTED, ≤30% PARTIAL, else REFUTED).
"""
function physics_prime_constellations_at(start::Integer, width::Integer)::Dict{String,Any}
    range_start = max(2, start)
    range_end   = start + max(50, width)
    primes = Int[]
    for n in range_start:(range_end - 1)
        if is_prime(n)
            push!(primes, n)
        end
    end
    twins = 0
    cousins = 0
    sexy = 0
    max_gap = 0
    for i in 2:length(primes)
        gap = primes[i] - primes[i - 1]
        if gap > max_gap
            max_gap = gap
        end
        if gap == 2; twins   += 1 end
        if gap == 4; cousins += 1 end
        if gap == 6; sexy    += 1 end
    end
    pairs = max(1, length(primes) - 1)
    twin_density  = Float64(twins) / pairs
    prime_density = Float64(length(primes)) / (range_end - range_start)
    ln_n = log(Float64(max(2, start)))
    hl_prediction = HARDY_LITTLEWOOD_TWIN_CONSTANT / (ln_n * ln_n) * width
    hl_deviation  = abs(twins - hl_prediction) / max(1.0, hl_prediction)

    # Threshold bands match JVM line 783-784 byte-for-byte.
    status = hl_deviation <= 0.15 ? "SUPPORTED" :
             hl_deviation <= 0.30 ? "PARTIAL" : "REFUTED"

    return Dict{String,Any}(
        "primaryValue"      => hl_deviation,
        "secondaryValue"    => prime_density,
        "tertiaryValue"     => Float64(max_gap),
        "reviewValue"       => hl_deviation,
        "claimStatus"       => status,
        "theoryConsistent"  => isfinite(hl_deviation) &&
                               isfinite(prime_density) &&
                               isfinite(Float64(max_gap)),
        # Detail fields the JVM caller uses to compose the findings prose.
        "windowStart"       => range_start,
        "windowEnd"         => range_end,
        "primeCount"        => length(primes),
        "twinCount"         => twins,
        "cousinCount"       => cousins,
        "sexyCount"         => sexy,
        "maxGap"            => max_gap,
        "twinDensity"       => twin_density,
        "primeDensity"      => prime_density,
        "hlPrediction"      => hl_prediction,
        "hlDeviation"       => hl_deviation,
    )
end

"""
    physics_prime_constellations(seed_start, width, sp) -> Dict

Outer search wrapper that probes `candidateBreadth` windows spaced
by `stepSize * 200`, returning the window with the largest
primaryValue (H-L deviation). Matches MathPhysicsEngine.primeConstellations
line 749-758.
"""
function physics_prime_constellations(seed_start::Integer, width::Integer,
                                       sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        start = max(2, seed_start + Int(floor(i * step_size * 200)))
        r = physics_prime_constellations_at(start, width)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_prime_constellations_at(seed_start, width) : best
end

"""
    physics_compute(domain, topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

Top-level dispatch. Returns the numeric scalars dict on ported
(domain, topicIdx) pairs; returns `nothing` to signal not-ported so
the handler can surface a 404-equivalent and the JVM falls back to
legacy.

Ported (domain, topicIdx) pairs in this slice:
  MATHEMATICS / 0 → physics_prime_constellations(param1*10, param1*15, sp)

Future slices (§28.5b continuations) extend this dispatch with
additional topics; the wire shape stays the same.
"""
function physics_compute(domain::String, topic_idx::Integer,
                          param1::Integer, param2::Real,
                          sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if domain == "MATHEMATICS"
        if topic_idx == 0
            return physics_prime_constellations(param1 * 10, param1 * 15, sp)
        end
    end
    # Not ported. JVM caller falls back to in-process MathPhysicsEngine.
    return nothing
end
