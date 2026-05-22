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

# ---------------------------------------------------------------------
# MATHEMATICS topic 1 — collatzStoppingTimes (line 802-849)
# ---------------------------------------------------------------------

"""
    physics_collatz_stopping_at(start, count) -> Dict

Inner per-window: walk Collatz from each integer in
[max(1,start), start+count), tracking max steps, Welford mean of
steps, count > 100 steps, and allTerminated flag. Status =
SUPPORTED iff allTerminated, else PARTIAL.
"""
function physics_collatz_stopping_at(start::Integer, count::Integer)::Dict{String,Any}
    max_steps = 0
    max_steps_at = start
    long_traj = 0
    w_count = 0
    all_terminated = true
    w_mean = 0.0
    w_m2   = 0.0
    range_start = max(1, start)
    for n in range_start:(start + count - 1)
        x = Int64(n)
        steps = 0
        while x != 1 && steps < 10_000
            x = x % 2 == 0 ? x ÷ 2 : 3 * x + 1
            steps += 1
        end
        if x != 1
            all_terminated = false
        end
        w_count += 1
        delta = Float64(steps) - w_mean
        w_mean += delta / w_count
        w_m2 += delta * (Float64(steps) - w_mean)
        if steps > max_steps
            max_steps = steps
            max_steps_at = n
        end
        if steps > 100
            long_traj += 1
        end
    end
    long_fraction = Float64(long_traj) / count
    status = all_terminated ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => Float64(max_steps),
        "secondaryValue"   => w_mean,
        "tertiaryValue"    => long_fraction,
        "reviewValue"      => Float64(max_steps),
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(Float64(max_steps)) &&
                              isfinite(w_mean) && isfinite(long_fraction),
        # Detail fields for the JVM caller's prose composition.
        "windowStart"      => start,
        "windowEnd"        => start + count,
        "maxSteps"         => max_steps,
        "maxStepsAt"       => max_steps_at,
        "avgSteps"         => w_mean,
        "longFraction"     => long_fraction,
        "allTerminated"    => all_terminated,
        "count"            => count,
    )
end

function physics_collatz_stopping_times(seed_start::Integer, count::Integer,
                                         sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        start = max(1, seed_start + Int(floor(i * step_size * 20)))
        r = physics_collatz_stopping_at(start, count)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_collatz_stopping_at(seed_start, count) : best
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 4 — happyNumbers (line 962-1017)
# ---------------------------------------------------------------------

"""
    physics_happy_numbers_at(start, count) -> Dict

Inner per-window: count happy numbers in [max(1,start), start+count),
track Welford mean of steps-to-1 (only over happy numbers). Returns
{deviation from 0.143, avgSteps, density}.
"""
function physics_happy_numbers_at(start::Integer, count::Integer)::Dict{String,Any}
    happy = 0
    happy_mean = 0.0
    happy_m2   = 0.0
    range_start = max(1, start)
    for n in range_start:(start + count - 1)
        x = Int64(n)
        steps = 0
        while x != 1 && x != 4 && steps < 1000
            sum_val = Int64(0)
            while x > 0
                d = x % 10
                sum_val += d * d
                x = x ÷ 10
            end
            x = sum_val
            steps += 1
        end
        if x == 1
            happy += 1
            delta = Float64(steps) - happy_mean
            happy_mean += delta / happy
            happy_m2 += delta * (Float64(steps) - happy_mean)
        end
    end
    density   = Float64(happy) / count
    avg_steps = happy > 0 ? happy_mean : 0.0
    deviation = abs(density - 0.1430)
    return Dict{String,Any}(
        # Note: status here is the inner OBSERVATIONAL (allFinite
        # default from the 4-arg constructor); outer wrapper
        # overwrites it via withFindingsAndClaim.
        "primaryValue"     => deviation,
        "secondaryValue"   => avg_steps,
        "tertiaryValue"    => density,
        "reviewValue"      => deviation,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(deviation) && isfinite(avg_steps) &&
                              isfinite(density),
        # Detail fields the outer wrapper + JVM caller use.
        "windowStart"      => start,
        "windowEnd"        => start + count,
        "happyCount"       => happy,
        "density"          => density,
        "deviation"        => deviation,
        "avgSteps"         => avg_steps,
        "count"            => count,
    )
end

function physics_happy_numbers(seed_start::Integer, count::Integer,
                                sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    max_deviation = 0.0
    sampled = 0
    for i in 0:(candidate_breadth - 1)
        start = max(1, seed_start + Int(floor(i * step_size * 200)))
        r = physics_happy_numbers_at(start, count)
        if r["primaryValue"] > max_deviation
            max_deviation = r["primaryValue"]
        end
        sampled += 1
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    if best === nothing
        best = physics_happy_numbers_at(seed_start, count)
    end
    # Outer wrapper overrides claimStatus per the JVM line 975-976
    # threshold bands on `maxDeviation` (NOT on the inner deviation).
    status = max_deviation <= 0.03 ? "SUPPORTED" :
             max_deviation <= 0.06 ? "PARTIAL" : "REFUTED"
    best["claimStatus"] = status
    best["maxDeviation"] = max_deviation
    best["sampled"] = sampled
    return best
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 9 (default) — squarefreeDensity (line 1173-1207)
# ---------------------------------------------------------------------

"""
    physics_squarefree_density(seed_start, width, sp) -> Dict

Search candidateBreadth windows of width `width` spaced by
stepSize*200. For each, count squarefree integers and compute the
density's deviation from the asymptotic 6/π². Returns scalars for
the WORST-deviation window plus the mean deviation. Status bands
match JVM line 1194-1195 (≤0.05 SUPPORTED, ≤0.10 PARTIAL else REFUTED).
"""
function physics_squarefree_density(seed_start::Integer, width::Integer,
                                     sp::Dict{String,Any})::Dict{String,Any}
    asymptotic = 6.0 / (pi * pi)
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_start = seed_start
    best_density = 0.0
    samples = 0
    sum_dev = 0.0
    for i in 0:(candidate_breadth - 1)
        start = max(1, seed_start + Int(floor(i * step_size * 200)))
        end_  = start + width
        count = 0
        for n in start:(end_ - 1)
            if is_squarefree(n)
                count += 1
            end
        end
        density = Float64(count) / max(1, end_ - start)
        dev = abs(density - asymptotic)
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_start = start
            best_density = density
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    status = best_dev <= 0.05 ? "SUPPORTED" :
             best_dev <= 0.10 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => mean_dev,
        "tertiaryValue"    => best_density,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(mean_dev) &&
                              isfinite(best_density),
        # Detail fields.
        "bestStart"        => best_start,
        "bestDev"          => best_dev,
        "bestDensity"      => best_density,
        "meanDev"          => mean_dev,
        "samples"          => samples,
        "asymptotic"       => asymptotic,
        "width"            => width,
        "seedStart"        => seed_start,
    )
end

"""
    physics_compute(domain, topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

Top-level dispatch. Returns the numeric scalars dict on ported
(domain, topicIdx) pairs; returns `nothing` to signal not-ported so
the handler can surface a 404-equivalent and the JVM falls back to
legacy.

Ported (domain, topicIdx) pairs:
  MATHEMATICS / 0 → physics_prime_constellations(param1*10, param1*15, sp)
  MATHEMATICS / 1 → physics_collatz_stopping_times(param1*3, 60, sp)
  MATHEMATICS / 4 → physics_happy_numbers(param1*7, 150, sp)
  MATHEMATICS / 9+ → physics_squarefree_density(max(10, param1*6), 200, sp)
    (topic 9 is the `default` branch in JVM computeMath — any topic
     index ≥ 9 in MATHEMATICS routes here)

Future slices extend this dispatch with topics 2 (goldbach),
3 (aliquot), 5 (mertensPartialSums), 6 (divisorSumAverage),
7 (riemannEulerProduct), 8 (sternBrocotDepth), and the eight
other domains (PHYSICS, MATERIALS, INTERDISCIPLINARY, BIOLOGY,
CHEMISTRY, ARCHITECTURE_*). The wire shape stays the same.
"""
function physics_compute(domain::String, topic_idx::Integer,
                          param1::Integer, param2::Real,
                          sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if domain == "MATHEMATICS"
        if topic_idx == 0
            return physics_prime_constellations(param1 * 10, param1 * 15, sp)
        elseif topic_idx == 1
            return physics_collatz_stopping_times(param1 * 3, 60, sp)
        elseif topic_idx == 4
            return physics_happy_numbers(param1 * 7, 150, sp)
        elseif topic_idx >= 9
            # JVM computeMath uses topic 9+ as the `default` branch
            # which routes to squarefreeDensity.
            return physics_squarefree_density(max(10, param1 * 6), 200, sp)
        end
    end
    # Not ported. JVM caller falls back to in-process MathPhysicsEngine.
    return nothing
end
