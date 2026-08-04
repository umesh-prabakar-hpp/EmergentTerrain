# =====================================================================
# src/physics_mathematics.jl — Phase 28 §28.5b — MATHEMATICS domain
# =====================================================================
#
# All MATHEMATICS-domain (domain, topicIdx) implementations for
# physics_compute. Split out of physics.jl in §28.5b-cont3 so the
# dispatch file stays small and each domain port can land in its own
# file (physics_physics.jl, physics_biology.jl, ...).
#
# Wire contract (matches §28.5a /predicate/scan): numeric scalars +
# ClaimStatus name only. The JVM caller composes the findings /
# hypothesis / equation prose locally — avoids byte-identical-format
# friction between Java String.format and Julia @sprintf.

# Hardy-Littlewood twin-prime constant, 2·C₂. Same digits the JVM
# uses; bump in lockstep.
const HARDY_LITTLEWOOD_TWIN_CONSTANT = 1.320323631693739

# ---------------------------------------------------------------------
# MATHEMATICS topic 0 — primeConstellations (line 749-797)
# ---------------------------------------------------------------------

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
        "wM2"              => w_m2,   # §28.6b — Welford M2 for JVM fmtMean
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
# MATHEMATICS topic 2 — goldbachPartitions (line 853-891)
# ---------------------------------------------------------------------

"""
    physics_goldbach_partitions_at(even_start, count) -> Dict

Inner: for each even n in [even_start, even_start + 2*count),
count Goldbach partitions (pairs of primes summing to n). Returns
{contrast = avg/max(1,minParts), avg, singleCount}.
"""
function physics_goldbach_partitions_at(even_start::Integer, count::Integer)::Dict{String,Any}
    es = even_start % 2 != 0 ? even_start + 1 : even_start
    es = es < 4 ? 4 : es
    min_parts = typemax(Int)
    min_parts_at = es
    single_count = 0
    tested = 0
    w_mean = 0.0
    w_m2   = 0.0
    n = es
    while n < es + count * 2
        parts = 0
        p = 2
        while p <= n ÷ 2
            if is_prime(p) && is_prime(n - p)
                parts += 1
            end
            p += 1
        end
        tested += 1
        delta = Float64(parts) - w_mean
        w_mean += delta / tested
        w_m2 += delta * (Float64(parts) - w_mean)
        if parts < min_parts
            min_parts = parts
            min_parts_at = n
        end
        if parts == 1
            single_count += 1
        end
        n += 2
    end
    avg = w_mean
    contrast = avg / max(1.0, Float64(min_parts))
    # 4-arg ResearchResult → status defaults to OBSERVATIONAL when finite.
    return Dict{String,Any}(
        "primaryValue"     => contrast,
        "secondaryValue"   => avg,
        "tertiaryValue"    => Float64(single_count),
        "reviewValue"      => contrast,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(contrast) && isfinite(avg),
        # Detail fields.
        "evenStart"        => es,
        "evenEnd"          => es + count * 2,
        "minParts"         => min_parts == typemax(Int) ? 0 : min_parts,
        "minPartsAt"       => min_parts_at,
        "singleCount"      => single_count,
        "tested"           => tested,
        "avg"              => avg,
        "wM2"              => w_m2,   # §28.6b — Welford M2 for JVM fmtMean
    )
end

function physics_goldbach_partitions(seed_even::Integer, count::Integer,
                                      sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        even = max(4, seed_even + Int(floor(i * step_size * 40)))
        if even % 2 != 0
            even += 1
        end
        r = physics_goldbach_partitions_at(even, count)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_goldbach_partitions_at(seed_even, count) : best
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 3 — aliquotSequences (line 895-958)
# ---------------------------------------------------------------------
#
# Walks the aliquot sequence x → σ(x)−x (proper-divisor sum) from
# every n in [start, start+count). Outcomes counted per JVM:
#   terminate — reached x ≤ 1 within 200 steps;
#   perfect   — σ(x)−x = x (sequence is a 1-cycle);
#   cycle     — σ(x)−x is in the seen-set (any other cycle);
#   escape    — sequence grew past ALIQUOT_X_CAP, or hit the step cap
#               without terminating.
#
# Subtle JVM detail (line 941-942): `seen.contains(sigma)` is checked
# BEFORE `seen.add(x)`. So at iteration k:
#   - we compare the NEXT value (sigma of current x) against the set
#     of all previously visited values;
#   - then we add the CURRENT x (not sigma) into the set;
#   - then x := sigma.
# Net effect: cycle is detected the step before we'd revisit a prior
# value. Port preserves this ordering exactly.

# Aliquot sequences can grow without bound — cap x to avoid O(√x)
# factoring of huge numbers. Matches JVM line 924.
const ALIQUOT_X_CAP = Int64(1_000_000)

"""
    physics_aliquot_sequences_at(start, count) -> Dict

Inner per-window: walks aliquot sequences from `count` consecutive
seeds, counting terminate / perfect / cycle / escape outcomes.
Primary value is the anomaly fraction (cycle + escape) / count;
secondary is the terminate fraction.
"""
function physics_aliquot_sequences_at(start::Integer, count::Integer)::Dict{String,Any}
    terminate = 0
    perfect   = 0
    cycle     = 0
    escape    = 0
    for n in start:(start + count - 1)
        x = Int64(n)
        seen = Set{Int64}()
        steps = 0
        done = false
        while steps < 200 && !done
            if x <= 1
                terminate += 1; done = true; break
            end
            if x > ALIQUOT_X_CAP
                escape += 1; done = true; break
            end
            # sigma = σ(x) − x is the aliquot sum (proper-divisor sum).
            # JVM lines 936-939 inline the trial-division sweep starting
            # at d = 2 with sigma = 1; mirror that byte-for-byte (NOT
            # sigma_sum, which would still match here, but we keep the
            # exact JVM loop shape in case its arithmetic differs at
            # the int-overflow boundary).
            sigma = Int64(1)
            d = Int64(2)
            while d * d <= x
                if x % d == 0
                    sigma += d
                    if d != x ÷ d
                        sigma += x ÷ d
                    end
                end
                d += 1
            end
            if sigma == x
                perfect += 1; done = true; break
            end
            if sigma in seen
                cycle += 1; done = true; break
            end
            push!(seen, x)
            x = sigma
            steps += 1
        end
        if !done
            escape += 1
        end
    end
    terminate_frac = Float64(terminate) / count
    anomaly_frac   = Float64(cycle + escape) / count
    range_end = start + count
    # 4-arg ResearchResult — JVM passes (anomalyFrac, terminateFrac, cycle).
    # Status defaults to OBSERVATIONAL when finite; the outer wrapper
    # overrides it.
    return Dict{String,Any}(
        "primaryValue"     => anomaly_frac,
        "secondaryValue"   => terminate_frac,
        "tertiaryValue"    => Float64(cycle),
        "reviewValue"      => anomaly_frac,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(anomaly_frac) &&
                              isfinite(terminate_frac) &&
                              isfinite(Float64(cycle)),
        # Detail fields.
        "windowStart"      => start,
        "windowEnd"        => range_end,
        "terminate"        => terminate,
        "perfect"          => perfect,
        "cycle"            => cycle,
        "escape"           => escape,
        "terminateFrac"    => terminate_frac,
        "anomalyFrac"      => anomaly_frac,
        "count"            => count,
    )
end

"""
    physics_aliquot_sequences(seed_start, count, sp) -> Dict

Outer search: probes candidateBreadth windows spaced by
stepSize*15. Tracks first/last anomaly fraction to test the
range-start trend claim. Status bands (JVM line 911-913):
  predominantTerminates = secondary ≥ 0.5
  trendIncreases = lastAnomaly ≥ firstAnomaly + 0.05
  !predominantTerminates → REFUTED
  trendIncreases → SUPPORTED
  else → PARTIAL
"""
function physics_aliquot_sequences(seed_start::Integer, count::Integer,
                                    sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    first_anomaly = NaN
    last_anomaly  = NaN
    sampled = 0
    for i in 0:(candidate_breadth - 1)
        start = max(2, seed_start + Int(floor(i * step_size * 15)))
        r = physics_aliquot_sequences_at(start, count)
        if isnan(first_anomaly)
            first_anomaly = r["primaryValue"]
        end
        last_anomaly = r["primaryValue"]
        sampled += 1
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    if best === nothing
        best = physics_aliquot_sequences_at(seed_start, count)
    end
    predominantly_terminates = best["secondaryValue"] >= 0.5
    trend_increases = !isnan(first_anomaly) &&
                      last_anomaly >= first_anomaly + 0.05
    status = !predominantly_terminates ? "REFUTED" :
             trend_increases ? "SUPPORTED" : "PARTIAL"
    best["claimStatus"] = status
    best["sampled"] = sampled
    best["firstAnomaly"] = first_anomaly
    best["lastAnomaly"] = last_anomaly
    best["predominantlyTerminates"] = predominantly_terminates
    best["trendIncreases"] = trend_increases
    return best
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
        "happyM2"          => happy_m2,   # §28.6b — Welford M2 for JVM fmtMean
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
# MATHEMATICS topic 5 — mertensPartialSums (line 1022-1053)
# ---------------------------------------------------------------------

"""
    mertens_partial_sum(x) -> Int64

M(x) = Σ_{n=1..x} μ(n). Port of MathPhysicsEngine.mertensPartialSum
(line 1211). Calls `mobius_of` per n.
"""
function mertens_partial_sum(x::Integer)::Int64
    m = Int64(0)
    for n in 1:x
        m += mobius_of(n)
    end
    return m
end

"""
    physics_mertens_partial_sums(seed_start, width, sp) -> Dict

Sweeps candidateBreadth values of x spaced by stepSize*50, each
producing M(x) and the ratio |M(x)|/√x. The Mertens conjecture
heuristic says this ratio stays within 2.0 for the tested range.
Status bands (JVM line 1038-1040):
  ≤2.0 → SUPPORTED, ≤4.0 → PARTIAL, else REFUTED.
"""
function physics_mertens_partial_sums(seed_start::Integer, width::Integer,
                                       sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_ratio = 0.0
    best_x = seed_start + width
    best_m = Int64(0)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 windows for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        start = max(1, seed_start + Int(floor(i * step_size * 50)))
        end_  = start + width
        m = mertens_partial_sum(end_)
        ratio = abs(Float64(m)) / sqrt(max(1.0, Float64(end_)))
        samples += 1
        if ratio > best_ratio
            best_ratio = ratio
            best_x = end_
            best_m = m
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("x" => end_, "M" => m, "ratio" => ratio))
        end
    end
    within_bound = best_ratio <= 2.0
    status = within_bound ? "SUPPORTED" :
             best_ratio <= 4.0 ? "PARTIAL" : "REFUTED"
    # JVM line 1048: 6-arg ResearchResult constructor →
    #   primary=bestRatio, secondary=bestM, tertiary=bestX,
    #   review=bestRatio, status=status.
    return Dict{String,Any}(
        "primaryValue"     => best_ratio,
        "secondaryValue"   => Float64(best_m),
        "tertiaryValue"    => Float64(best_x),
        "reviewValue"      => best_ratio,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_ratio) &&
                              isfinite(Float64(best_m)) &&
                              isfinite(Float64(best_x)),
        # Detail fields.
        "bestRatio"        => best_ratio,
        "bestX"            => best_x,
        "bestM"            => best_m,
        "samples"          => samples,
        "width"            => width,
        "seedStart"        => seed_start,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 6 — divisorSumAverage (line 1056-1091)
# ---------------------------------------------------------------------

"""
    physics_divisor_sum_average(seed_n, width, sp) -> Dict

Sweeps N upward through candidateBreadth values spaced by stepSize*40.
For each N: computes total = Σ_{n=1..N} σ(n) where σ(n) is the FULL
divisor sum (includes 1 and n). Compares (total / N²) to π²/12.
Tracks worst (largest deviation) sample. Status bands match JVM line
1078-1079: ≤0.10 SUPPORTED, ≤0.20 PARTIAL, else REFUTED.
"""
function physics_divisor_sum_average(seed_n::Integer, width::Integer,
                                      sp::Dict{String,Any})::Dict{String,Any}
    asymptotic = pi * pi / 12.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_n = seed_n
    best_avg = 0.0
    samples = 0
    sum_dev = 0.0
    for i in 0:(candidate_breadth - 1)
        N = max(20, seed_n + Int(floor(i * step_size * 40)))
        # sigma_sum returns the ALIQUOT sum (σ(n)−n); JVM adds n back
        # to recover the full divisor sum. Mirror that exactly.
        total = Int64(0)
        for n in 1:N
            total += sigma_sum(n) + n
        end
        avg_over_n = Float64(total) / (Float64(N) * Float64(N))
        dev = abs(avg_over_n - asymptotic) / asymptotic
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_n = N
            best_avg = avg_over_n
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    status = best_dev <= 0.10 ? "SUPPORTED" :
             best_dev <= 0.20 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => mean_dev,
        "tertiaryValue"    => best_avg,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(mean_dev) &&
                              isfinite(best_avg),
        "bestN"            => best_n,
        "bestAvg"          => best_avg,
        "asymptotic"       => asymptotic,
        "samples"          => samples,
        "width"            => width,
        "seedN"            => seed_n,   # §28.6b — JVM hypothesis prose needs the seed
    )
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 7 — riemannEulerProduct (line 1094-1133)
# ---------------------------------------------------------------------

"""
    physics_riemann_euler_product(seed_s, sp) -> Dict

For each s in `seed_s + i * stepSize * 0.2` (clamped to >= 1.1):
compute ζ_truncated(s) = Σ_{n=1..5000} n⁻ˢ; compute Euler product
∏_{p prime ≤ 200} (1 − p⁻ˢ)⁻¹. The ratio (eulerProd / zetaSum)
should approach 1 as truncation → ∞. Tracks worst deviation.
Status bands: ≤0.05 SUPPORTED, ≤0.15 PARTIAL, else REFUTED.
"""
function physics_riemann_euler_product(seed_s::Real, sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_s = Float64(seed_s)
    best_ratio = 1.0
    samples = 0
    sum_dev = 0.0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 s-values for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        s = max(1.1, Float64(seed_s) + i * step_size * 0.2)
        truncation = 5000
        zeta_sum = 0.0
        for n in 1:truncation
            zeta_sum += Float64(n) ^ (-s)
        end
        euler_prod = 1.0
        for p in 2:200
            is_prime(p) || continue
            euler_prod *= 1.0 / (1.0 - Float64(p) ^ (-s))
        end
        ratio = euler_prod / zeta_sum
        dev = abs(ratio - 1.0)
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_s = s
            best_ratio = ratio
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("s" => s, "ratio" => ratio))
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    within_band = best_dev <= 0.05
    status = within_band ? "SUPPORTED" :
             best_dev <= 0.15 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => best_ratio,
        "tertiaryValue"    => best_s,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(best_ratio) &&
                              isfinite(best_s),
        "bestS"            => best_s,
        "bestRatio"        => best_ratio,
        "meanDev"          => mean_dev,
        "samples"          => samples,
        "seedS"            => seed_s,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# MATHEMATICS topic 8 — sternBrocotDepth (line 1136-1170)
# ---------------------------------------------------------------------

"""
    physics_stern_brocot_depth(seed_q, sp) -> Dict

For each candidate index i: q = max(2, seed_q + i*stepSize*50),
p = 1 + (i*7) % max(2, q-1). Compute continued-fraction depth via
stern_brocot_depth_of. Tracks max depth across all sampled rationals.

Status: maxDepth in [5, 50) AND bestQ < 1_000_000 → SUPPORTED;
shallower (<5) under that bound → PARTIAL; deeper or larger-q →
REFUTED.
"""
function physics_stern_brocot_depth(seed_q::Integer, sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_depth = 0
    best_p = 0
    best_q = seed_q
    samples = 0
    sum_depth = Int64(0)
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 rationals for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        q = max(2, seed_q + Int(floor(i * step_size * 50)))
        p = 1 + (i * 7) % max(2, q - 1)
        depth = stern_brocot_depth_of(p, q)
        sum_depth += depth
        samples += 1
        if depth > max_depth
            max_depth = depth
            best_p = p
            best_q = q
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("p" => p, "q" => q, "depth" => depth))
        end
    end
    mean_depth = samples > 0 ? Float64(sum_depth) / samples : 0.0
    within_bound = max_depth < 50 && best_q < 1_000_000
    status = within_bound ?
        (max_depth >= 5 ? "SUPPORTED" : "PARTIAL") :
        "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => Float64(max_depth),
        "secondaryValue"   => mean_depth,
        "tertiaryValue"    => Float64(best_q),
        "reviewValue"      => Float64(max_depth),
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(Float64(max_depth)) &&
                              isfinite(mean_depth) &&
                              isfinite(Float64(best_q)),
        "maxDepth"         => max_depth,
        "bestP"            => best_p,
        "bestQ"            => best_q,
        "meanDepth"        => mean_depth,
        "samples"          => samples,
        "seedQ"            => seed_q,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
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
    physics_compute_mathematics(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

MATHEMATICS-domain dispatch. Mirrors JVM `computeMath` (line 730)
exactly — including the `default → squarefreeDensity` branch for
topic 9+. Returns `nothing` for indexes that fall outside any
case (currently impossible since topic 9+ catches everything; kept
for symmetry with the top-level domain dispatch).
"""
function physics_compute_mathematics(topic_idx::Integer,
                                      param1::Integer, param2::Real,
                                      sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        return physics_prime_constellations(param1 * 10, param1 * 15, sp)
    elseif topic_idx == 1
        return physics_collatz_stopping_times(param1 * 3, 60, sp)
    elseif topic_idx == 2
        # JVM line 735: goldbachPartitions(max(4, param1*4),
        # (int) param2 * 10 + 20). Java's (int) TRUNCATES toward zero; the Julia
        # equivalent is trunc(Int, …), NOT Int(…) — the latter throws
        # InexactError on a fractional param2 (e.g. 3.6686 from the live sim).
        seed_even = max(4, param1 * 4)
        count = trunc(Int, param2) * 10 + 20
        return physics_goldbach_partitions(seed_even, count, sp)
    elseif topic_idx == 3
        return physics_aliquot_sequences(max(2, param1), 40, sp)
    elseif topic_idx == 4
        return physics_happy_numbers(param1 * 7, 150, sp)
    elseif topic_idx == 5
        return physics_mertens_partial_sums(max(10, param1 * 5), 200, sp)
    elseif topic_idx == 6
        return physics_divisor_sum_average(max(10, param1 * 4), 150, sp)
    elseif topic_idx == 7
        return physics_riemann_euler_product(1.5 + param2 / 9.0, sp)
    elseif topic_idx == 8
        return physics_stern_brocot_depth(max(2, param1), sp)
    else
        # JVM computeMath uses topic 9+ as the `default` branch which
        # routes to squarefreeDensity.
        return physics_squarefree_density(max(10, param1 * 6), 200, sp)
    end
end
