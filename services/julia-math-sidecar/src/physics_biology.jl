# =====================================================================
# src/physics_biology.jl — Phase 28 §28.5f — BIOLOGY domain
# =====================================================================
#
# Ports of MathPhysicsEngine.computeBiology topics. 5 topics total
# (BIOLOGY_TOPICS array length). Wire contract + bit-identity discipline
# match the other per-domain files.

# ---------------------------------------------------------------------
# BIOLOGY topic 0 — verhulstLogistic (line 2616-2645)
# ---------------------------------------------------------------------
#
# Closed-form Verhulst dN/dt = r·N·(1 − N/K):
#   N(t) = K / (1 + ((K − N₀)/N₀)·exp(−r·t)), N₀ = K/20.
# Sweep t = max(0.1, i·stepSize·2); track worst |N(t)−K|/K.

"""
    physics_biology_verhulst_logistic(r, K, sp) -> Dict
"""
function physics_biology_verhulst_logistic(r::Real, K::Real,
                                            sp::Dict{String,Any})::Dict{String,Any}
    N0 = Float64(K) / 20.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_t = 0.0
    best_N = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        t = max(0.1, i * step_size * 2.0)
        Nt = Float64(K) / (1 + ((Float64(K) - N0) / N0) * exp(-Float64(r) * t))
        deviation = abs(Nt - Float64(K)) / Float64(K)
        samples += 1
        if deviation > best_dev
            best_dev = deviation
            best_t = t
            best_N = Nt
        end
    end
    status = best_dev < 0.99 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => best_N,
        "tertiaryValue"    => Float64(K),
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(best_N) && isfinite(Float64(K)),
        "r"                => Float64(r),
        "K"                => Float64(K),
        "N0"               => N0,
        "bestT"            => best_t,
        "bestN"            => best_N,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# BIOLOGY topic 1 — lotkaVolterra (line 2648-2693)
# ---------------------------------------------------------------------
#
# Discrete-time Euler integration over 400 steps at dt=0.05. Predator
# δ=0.01, γ=0.3; prey α swept upward. Tracks ratio maxX/max(0.001, minX).
# This is the second multi-step Euler integration after PHYSICS topic 0
# (projectile-with-drag) — same bit-identity claims apply.

"""
    physics_biology_lotka_volterra(alpha, beta, sp) -> Dict
"""
function physics_biology_lotka_volterra(alpha::Real, beta::Real,
                                         sp::Dict{String,Any})::Dict{String,Any}
    gamma = 0.3
    delta = 0.01
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_ratio = 0.0
    best_alpha = Float64(alpha)
    best_peak_x = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        a = max(0.1, Float64(alpha) + i * step_size * 0.05)
        x = 40.0
        y = 9.0
        dt = 0.05
        max_x = x
        min_x = x
        for _ in 1:400
            dx = (a * x - Float64(beta) * x * y) * dt
            dy = (delta * x * y - gamma * y) * dt
            x = max(0.0, x + dx)
            y = max(0.0, y + dy)
            if x > max_x; max_x = x end
            if x < min_x; min_x = x end
        end
        ratio = max_x / max(0.001, min_x)
        samples += 1
        if ratio > best_ratio
            best_ratio = ratio
            best_alpha = a
            best_peak_x = max_x
        end
    end
    physical = best_ratio < 1e6 && isfinite(best_peak_x)
    status = !physical ? "REFUTED" :
             best_ratio >= 1.5 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_ratio,
        "secondaryValue"   => best_peak_x,
        "tertiaryValue"    => best_alpha,
        "reviewValue"      => best_ratio,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_ratio) && isfinite(best_peak_x) &&
                              isfinite(best_alpha),
        "bestRatio"        => best_ratio,
        "bestAlpha"        => best_alpha,
        "bestPeakX"        => best_peak_x,
        "beta"             => Float64(beta),
        "gamma"            => gamma,
        "delta"            => delta,
        "samples"          => samples,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# BIOLOGY topic 2 — michaelisMenten (line 2696-2734)
# ---------------------------------------------------------------------
#
# Two-point K_m recovery from v = V_max·[S]/(K_m+[S]). For each i,
# add sin(i·0.7)·0.02 noise; estimate K_m via K_m = S·(V_max/v − 1)
# averaged over two substrate points. Track best recovery error.

"""
    physics_biology_michaelis_menten(Km, Vmax, sp) -> Dict
"""
function physics_biology_michaelis_menten(Km::Real, Vmax::Real,
                                           sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    best_recovery_err = floatmax(Float64)
    best_Km_recovered = Float64(Km)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        S1 = Float64(Km) * 0.5
        S2 = Float64(Km) * 4.0
        noise = sin(i * 0.7) * 0.02
        v1 = Float64(Vmax) * S1 / (Float64(Km) + S1) * (1.0 + noise)
        v2 = Float64(Vmax) * S2 / (Float64(Km) + S2) * (1.0 + noise)
        Km1 = S1 * (Float64(Vmax) / v1 - 1.0)
        Km2 = S2 * (Float64(Vmax) / v2 - 1.0)
        Km_est = (Km1 + Km2) / 2.0
        err = abs(Km_est - Float64(Km)) / Float64(Km)
        samples += 1
        if err < best_recovery_err
            best_recovery_err = err
            best_Km_recovered = Km_est
        end
    end
    status = best_recovery_err < 0.10 ? "SUPPORTED" :
             best_recovery_err < 0.30 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_recovery_err,
        "secondaryValue"   => best_Km_recovered,
        "tertiaryValue"    => Float64(Vmax),
        "reviewValue"      => best_recovery_err,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_recovery_err) && isfinite(best_Km_recovered) &&
                              isfinite(Float64(Vmax)),
        "Km"               => Float64(Km),
        "Vmax"             => Float64(Vmax),
        "bestRecoveryErr"  => best_recovery_err,
        "bestKmRecovered"  => best_Km_recovered,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# BIOLOGY topic 3 — kleiberAllometric (line 2737-2776)
# ---------------------------------------------------------------------
#
# R = R₀·M^0.75. Generate (M, R) pairs over a 2^x sweep, then fit
# log-log slope and compare to 0.75. Two pow() calls per inner iteration.

"""
    physics_biology_kleiber_allometric(seed_M, sp) -> Dict
"""
function physics_biology_kleiber_allometric(seed_M::Real, sp::Dict{String,Any})::Dict{String,Any}
    R0 = 3.5
    true_exp = 0.75
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    # JVM line 2741: ArrayList of double[]. We collect into parallel
    # Vectors and do a single-pass regression at the end (matches the
    # JVM's two-pass shape — generate then regress).
    Ms = Vector{Float64}(undef, candidate_breadth)
    Rs = Vector{Float64}(undef, candidate_breadth)
    for i in 0:(candidate_breadth - 1)
        # JVM line 2744: `seedM * Math.pow(2.0, i*stepSize - 4)`.
        # Fractional pow — same case as basquin/riemann.
        M = max(0.01, Float64(seed_M) * 2.0^(i * step_size - 4))
        R = R0 * M^true_exp * (1.0 + sin(i * 0.41) * 0.03)
        Ms[i + 1] = M
        Rs[i + 1] = R
    end
    n = length(Ms)
    sum_logM = 0.0
    sum_logR = 0.0
    sum_logMlogR = 0.0
    sum_logMsq = 0.0
    for k in 1:n
        logM = log(Ms[k])
        logR = log(Rs[k])
        sum_logM += logM
        sum_logR += logR
        sum_logMlogR += logM * logR
        sum_logMsq += logM * logM
    end
    # JVM line 2760-2761: denominator floored at 1e-12.
    slope = (n * sum_logMlogR - sum_logM * sum_logR) /
            max(1e-12, n * sum_logMsq - sum_logM * sum_logM)
    deviation = abs(slope - true_exp)
    status = deviation < 0.05 ? "SUPPORTED" :
             deviation < 0.10 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => deviation,
        "secondaryValue"   => slope,
        "tertiaryValue"    => R0,
        "reviewValue"      => deviation,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(deviation) && isfinite(slope) && isfinite(R0),
        "R0"               => R0,
        "trueExp"          => true_exp,
        "slope"            => slope,
        "deviation"        => deviation,
        "n"                => n,
        "seedM"            => Float64(seed_M),
    )
end

# ---------------------------------------------------------------------
# BIOLOGY topic 4 (default) — hardyWeinberg (line 2779-2816)
# ---------------------------------------------------------------------
#
# Track allele drift over 10 + i·step·4 generations. Per generation,
# add sin(gen·0.13 + i·0.5)·0.002 noise and clamp p to [0, 1].

"""
    physics_biology_hardy_weinberg(seed_p, sp) -> Dict
"""
function physics_biology_hardy_weinberg(seed_p::Real, sp::Dict{String,Any})::Dict{String,Any}
    # JVM line 2780: clamp seedP to [0.05, 0.95].
    p = max(0.05, min(0.95, Float64(seed_p)))
    q = 1.0 - p
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_drift = 0.0
    final_p = p
    samples = 0
    for i in 0:(candidate_breadth - 1)
        # JVM line 2787: `(int)(10 + i*stepSize*4)` — truncation.
        generations = trunc(Int, 10.0 + i * step_size * 4.0)
        pn = p
        local_max_drift = 0.0
        for gen in 0:(generations - 1)
            drift_step = sin(gen * 0.13 + i * 0.5) * 0.002
            pn = max(0.0, min(1.0, pn + drift_step))
            drift = abs(pn - p)
            if drift > local_max_drift
                local_max_drift = drift
            end
        end
        samples += 1
        if local_max_drift > max_drift
            max_drift = local_max_drift
            final_p = pn
        end
    end
    status = max_drift < 0.05 ? "SUPPORTED" :
             max_drift < 0.10 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_drift,
        "secondaryValue"   => final_p,
        "tertiaryValue"    => p,
        "reviewValue"      => max_drift,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_drift) && isfinite(final_p) && isfinite(p),
        "p"                => p,
        "q"                => q,
        "maxDrift"         => max_drift,
        "finalP"           => final_p,
        "samples"          => samples,
    )
end

"""
    physics_compute_biology(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

BIOLOGY-domain dispatch (line 2604). 5 topics total.

Ported (topicIdx) → JVM mapping:
  0 → verhulstLogistic(0.05 + param2/90, 100 + param1·5)
  1 → lotkaVolterra(0.4 + param2/30, 0.02 + param1/5000)
  2 → michaelisMenten(0.5 + param2/10, 1.0 + param1/25)
  3 → kleiberAllometric(0.5 + param1·0.5)
  4+ → hardyWeinberg(0.3 + param2/30)   (default branch)
"""
function physics_compute_biology(topic_idx::Integer,
                                  param1::Integer, param2::Real,
                                  sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        r = 0.05 + Float64(param2) / 90.0
        K = 100.0 + Float64(param1) * 5.0
        return physics_biology_verhulst_logistic(r, K, sp)
    elseif topic_idx == 1
        alpha = 0.4 + Float64(param2) / 30.0
        beta  = 0.02 + Float64(param1) / 5000.0
        return physics_biology_lotka_volterra(alpha, beta, sp)
    elseif topic_idx == 2
        Km   = 0.5 + Float64(param2) / 10.0
        Vmax = 1.0 + Float64(param1) / 25.0
        return physics_biology_michaelis_menten(Km, Vmax, sp)
    elseif topic_idx == 3
        seedM = 0.5 + Float64(param1) * 0.5
        return physics_biology_kleiber_allometric(seedM, sp)
    else
        # Topic 4+ — JVM `default` branch → hardyWeinberg.
        seedP = 0.3 + Float64(param2) / 30.0
        return physics_biology_hardy_weinberg(seedP, sp)
    end
end
