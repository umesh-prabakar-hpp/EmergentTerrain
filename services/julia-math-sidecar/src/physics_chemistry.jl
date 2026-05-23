# =====================================================================
# src/physics_chemistry.jl — Phase 28 §28.5g — CHEMISTRY domain
# =====================================================================
#
# Ports of MathPhysicsEngine.computeChemistry topics. 5 topics total.

# ---------------------------------------------------------------------
# CHEMISTRY topic 0 — hendersonHasselbalch (line 2832-2861)
# ---------------------------------------------------------------------

"""
    physics_chem_henderson_hasselbalch(true_pKa, sp) -> Dict
"""
function physics_chem_henderson_hasselbalch(true_pKa::Real,
                                             sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_err = floatmax(Float64)
    best_recovered = Float64(true_pKa)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 titration points for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        ratio = 10.0^(-1.0 + i * step_size * 0.2)
        pH = Float64(true_pKa) + log10(ratio) + sin(i * 0.3) * 0.01
        pKa_est = pH - log10(ratio)
        err = abs(pKa_est - Float64(true_pKa))
        samples += 1
        if err < best_err
            best_err = err
            best_recovered = pKa_est
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("ratio" => ratio, "pH" => pH, "pKaEst" => pKa_est))
        end
    end
    status = best_err < 0.05 ? "SUPPORTED" :
             best_err < 0.20 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_err,
        "secondaryValue"   => best_recovered,
        "tertiaryValue"    => Float64(true_pKa),
        "reviewValue"      => best_err,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_err) && isfinite(best_recovered),
        "truePKa"          => Float64(true_pKa),
        "bestErr"          => best_err,
        "bestRecovered"    => best_recovered,
        "samples"          => samples,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# CHEMISTRY topic 1 — nernstPotential (line 2864-2897)
# ---------------------------------------------------------------------

"""
    physics_chem_nernst_potential(E0, T, sp) -> Dict
"""
function physics_chem_nernst_potential(E0::Real, T::Real,
                                        sp::Dict{String,Any})::Dict{String,Any}
    R = 8.314
    F = 96485.0
    n = 1
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_E = Float64(E0)
    best_Q = 1.0
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 Q-samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        Q = 10.0^(-2.0 + i * step_size * 0.4)
        E = Float64(E0) - (R * Float64(T) / (n * F)) * log(Q)
        dev = abs(E - Float64(E0))
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_E = E
            best_Q = Q
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("Q" => Q, "E" => E))
        end
    end
    physical = best_dev <= 0.3
    status = !physical ? "REFUTED" :
             best_dev > 0.05 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => best_E,
        "tertiaryValue"    => best_Q,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(best_E) && isfinite(best_Q),
        "E0"               => Float64(E0),
        "T"                => Float64(T),
        "bestDev"          => best_dev,
        "bestE"            => best_E,
        "bestQ"            => best_Q,
        "samples"          => samples,
        "physical"         => physical,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# CHEMISTRY topic 2 — beerLambert (line 2900-2929)
# ---------------------------------------------------------------------

"""
    physics_chem_beer_lambert(true_eps, true_L, sp) -> Dict
"""
function physics_chem_beer_lambert(true_eps::Real, true_L::Real,
                                    sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_err = floatmax(Float64)
    best_recovered_eps = Float64(true_eps)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 concentration samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        c = 0.001 + i * step_size * 0.0005
        A = Float64(true_eps) * c * Float64(true_L) * (1.0 + sin(i * 0.27) * 0.01)
        eps_recovered = A / (c * Float64(true_L))
        err = abs(eps_recovered - Float64(true_eps)) / Float64(true_eps)
        samples += 1
        if err < best_err
            best_err = err
            best_recovered_eps = eps_recovered
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("c" => c, "A" => A, "epsRecovered" => eps_recovered))
        end
    end
    status = best_err < 0.05 ? "SUPPORTED" :
             best_err < 0.15 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_err,
        "secondaryValue"   => best_recovered_eps,
        "tertiaryValue"    => Float64(true_eps),
        "reviewValue"      => best_err,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_err) && isfinite(best_recovered_eps),
        "trueEps"          => Float64(true_eps),
        "trueL"            => Float64(true_L),
        "bestErr"          => best_err,
        "bestRecoveredEps" => best_recovered_eps,
        "samples"          => samples,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# CHEMISTRY topic 3 — equilibriumKc (line 2932-2966)
# ---------------------------------------------------------------------
#
# Van't Hoff integrated form ln(K₂/K₁) = -ΔH/R · (1/T₂ - 1/T₁) for
# ΔH = 30,000 J/mol (modest endotherm). Reference T = 298.15 K.

"""
    physics_chem_equilibrium_kc(Kc298, seed_T, sp) -> Dict
"""
function physics_chem_equilibrium_kc(Kc298::Real, seed_T::Real,
                                      sp::Dict{String,Any})::Dict{String,Any}
    R = 8.314
    delta_H = 30_000.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_drift = 0.0
    best_T = Float64(seed_T)
    best_Kc = Float64(Kc298)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 temperature samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        T = max(250.0, Float64(seed_T) + i * step_size * 10.0)
        ln_ratio = -delta_H / R * (1.0 / T - 1.0 / 298.15)
        Kc = Float64(Kc298) * exp(ln_ratio)
        drift = abs(log(Kc / Float64(Kc298)))
        samples += 1
        if drift > best_drift
            best_drift = drift
            best_T = T
            best_Kc = Kc
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("T" => T, "Kc" => Kc, "drift" => drift))
        end
    end
    physical = isfinite(best_Kc) && best_Kc > 0
    status = !physical ? "REFUTED" :
             best_drift > 0.3 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_drift,
        "secondaryValue"   => best_Kc,
        "tertiaryValue"    => best_T,
        "reviewValue"      => best_drift,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_drift) && isfinite(best_Kc) && isfinite(best_T),
        "Kc298"            => Float64(Kc298),
        "deltaH"           => delta_H,
        "bestDrift"        => best_drift,
        "bestT"            => best_T,
        "bestKc"           => best_Kc,
        "samples"          => samples,
        "physical"         => physical,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# CHEMISTRY topic 4 (default) — compressibilityZ (line 2969-3028)
# ---------------------------------------------------------------------
#
# Pitzer-Curl truncated virial: Z = 1 + B₀·Pr/Tr where B₀ = 0.083 −
# 0.422·Tr^(−1.6). Includes the Phase-10 clamp: when B₀ < 0, cap Pr
# at 0.95·Tr/|B₀| so Z stays > 0 (linearised model's validity envelope).

"""
    physics_chem_compressibility_z(seed_Tr, seed_Pr, sp) -> Dict
"""
function physics_chem_compressibility_z(seed_Tr::Real, seed_Pr::Real,
                                         sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_Tr = Float64(seed_Tr)
    best_Pr = Float64(seed_Pr)
    best_Z = 1.0
    samples = 0
    clamped_samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (Tr, Pr) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        Tr = max(0.5, Float64(seed_Tr) + i * step_size * 0.05)
        Pr_raw = max(0.1, Float64(seed_Pr) + i * step_size * 0.2)
        # Tr^(-1.6) is fractional pow — same case as basquin / riemann.
        B0 = 0.083 - 0.422 * Tr^(-1.6)
        Pr = Pr_raw
        if B0 < 0
            max_valid_Pr = 0.95 * Tr / abs(B0)
            if Pr_raw > max_valid_Pr
                Pr = max_valid_Pr
                clamped_samples += 1
            end
        end
        Z = 1.0 + B0 * Pr / Tr
        dev = abs(1.0 - Z)
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_Tr = Tr
            best_Pr = Pr
            best_Z = Z
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("Tr" => Tr, "Pr" => Pr, "Z" => Z))
        end
    end
    physical = best_dev <= 0.5
    status = !physical ? "REFUTED" :
             best_dev > 0.1 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => best_Z,
        "tertiaryValue"    => best_Pr,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(best_Z) && isfinite(best_Pr),
        "bestDev"          => best_dev,
        "bestTr"           => best_Tr,
        "bestPr"           => best_Pr,
        "bestZ"            => best_Z,
        "samples"          => samples,
        "clampedSamples"   => clamped_samples,
        "physical"         => physical,
        "seedTr"           => Float64(seed_Tr),   # §28.6b — JVM hypothesis prose
        "seedPr"           => Float64(seed_Pr),
        "sampleSweep"      => sample_sweep,         # §28.6b — JVM rebuilds the sweep prose
    )
end

"""
    physics_compute_chemistry(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

CHEMISTRY-domain dispatch (line 2820). 5 topics.

Ported (topicIdx) → JVM mapping:
  0 → hendersonHasselbalch(4.0 + param1/30)
  1 → nernstPotential(0.5 + param1/100, 298.0 + param2·5)
  2 → beerLambert(0.1 + param2/90, 1.0 + param1/20)
  3 → equilibriumKc(1.0 + param2/2, 298.0 + param1·3)
  4+ → compressibilityZ(0.7 + param1/200, 1.0 + param2/4)   (default)
"""
function physics_compute_chemistry(topic_idx::Integer,
                                    param1::Integer, param2::Real,
                                    sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        true_pKa = 4.0 + Float64(param1) / 30.0
        return physics_chem_henderson_hasselbalch(true_pKa, sp)
    elseif topic_idx == 1
        E0 = 0.5 + Float64(param1) / 100.0
        T  = 298.0 + Float64(param2) * 5.0
        return physics_chem_nernst_potential(E0, T, sp)
    elseif topic_idx == 2
        eps = 0.1 + Float64(param2) / 90.0
        L   = 1.0 + Float64(param1) / 20.0
        return physics_chem_beer_lambert(eps, L, sp)
    elseif topic_idx == 3
        Kc298 = 1.0 + Float64(param2) / 2.0
        seedT = 298.0 + Float64(param1) * 3.0
        return physics_chem_equilibrium_kc(Kc298, seedT, sp)
    else
        # Topic 4+ — JVM `default` branch → compressibilityZ.
        seedTr = 0.7 + Float64(param1) / 200.0
        seedPr = 1.0 + Float64(param2) / 4.0
        return physics_chem_compressibility_z(seedTr, seedPr, sp)
    end
end
