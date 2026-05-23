# =====================================================================
# src/physics_arch_terr.jl — Phase 28 §28.5h — ARCHITECTURE_TERRESTRIAL
# =====================================================================
#
# Ports of MathPhysicsEngine.computeArchTerrestrial. All 5 topics are
# generic over surface gravity g and atmospheric density ρ_atm per the
# F3.4 spec ([[architecture-generic-environments]] — saved in user
# memory): no Earth-anchored constants in the compute logic.

# ---------------------------------------------------------------------
# Topic 0 — beamDeflectionSelfWeight (line 3054-3092)
# ---------------------------------------------------------------------

"""
    physics_arch_terr_beam_deflection_self_weight(g_seed, sp) -> Dict
"""
function physics_arch_terr_beam_deflection_self_weight(g_seed::Real,
                                                        sp::Dict{String,Any})::Dict{String,Any}
    rho = 7850.0
    E = 200e9
    width = 0.2
    height = 0.4
    # h³ is integer exponent — match Math.pow(height, 3) with explicit h·h·h.
    I = (width * (height * height * height)) / 12.0
    A = width * height
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_ratio = 0.0
    best_L = 0.0
    best_g = Float64(g_seed)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (L, g) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        g = max(0.2, Float64(g_seed) + sin(i * 0.31) * 5.0)
        L = 2.0 + i * step_size * 0.5
        # L⁴ is integer exponent.
        delta = (rho * g * A * L * L * L * L) / (8.0 * E * I)
        ratio = delta / L
        samples += 1
        if ratio > best_ratio
            best_ratio = ratio
            best_L = L
            best_g = g
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("L" => L, "g" => g, "ratio" => ratio))
        end
    end
    status = best_ratio < 0.005 ? "SUPPORTED" :
             best_ratio < 0.01 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_ratio,
        "secondaryValue"   => best_L,
        "tertiaryValue"    => best_g,
        "reviewValue"      => best_ratio,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_ratio) && isfinite(best_L) && isfinite(best_g),
        "bestRatio"        => best_ratio,
        "bestL"            => best_L,
        "bestG"            => best_g,
        "samples"          => samples,
        "gSeed"            => Float64(g_seed),   # §28.6b — JVM hypothesis prose
        "sampleSweep"      => sample_sweep,       # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# Topic 1 — windLoadPressure (line 3095-3127)
# ---------------------------------------------------------------------

"""
    physics_arch_terr_wind_load_pressure(rho_atm_seed, sp) -> Dict
"""
function physics_arch_terr_wind_load_pressure(rho_atm_seed::Real,
                                               sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_pressure = 0.0
    best_v = 0.0
    best_rho = Float64(rho_atm_seed)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (ρ_atm, v) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        rho_atm = max(0.001, Float64(rho_atm_seed) + sin(i * 0.27) * 5.0)
        v = 5.0 + i * step_size * 3.0
        q = 0.5 * rho_atm * v * v
        samples += 1
        if q > best_pressure
            best_pressure = q
            best_v = v
            best_rho = rho_atm
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("rhoAtm" => rho_atm, "v" => v, "q" => q))
        end
    end
    physical = best_pressure <= 10_000.0
    status = !physical ? "REFUTED" :
             best_pressure > 500.0 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => best_pressure,
        "secondaryValue"   => best_v,
        "tertiaryValue"    => best_rho,
        "reviewValue"      => best_pressure,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_pressure) && isfinite(best_v) && isfinite(best_rho),
        "bestPressure"     => best_pressure,
        "bestV"            => best_v,
        "bestRho"          => best_rho,
        "samples"          => samples,
        "physical"         => physical,
        "rhoAtmSeed"       => Float64(rho_atm_seed),   # §28.6b — JVM hypothesis prose
        "sampleSweep"      => sample_sweep,             # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# Topic 2 — bearingCapacityTerzaghi (line 3130-3166)
# ---------------------------------------------------------------------

"""
    physics_arch_terr_bearing_capacity_terzaghi(g_seed, sp) -> Dict
"""
function physics_arch_terr_bearing_capacity_terzaghi(g_seed::Real,
                                                      sp::Dict{String,Any})::Dict{String,Any}
    cohesion = 20_000.0
    rho_soil = 1900.0
    Nc = 17.7
    Nq = 7.4
    Ngamma = 5.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_Q = 0.0
    best_B = 1.0
    best_g = Float64(g_seed)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (g, B) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        g = max(0.5, Float64(g_seed) + sin(i * 0.23) * 3.0)
        gamma_soil = rho_soil * g
        D = 1.0 + i * step_size * 0.5
        B = 1.0 + i * step_size * 0.5
        q_u = cohesion * Nc + gamma_soil * D * Nq + 0.5 * gamma_soil * B * Ngamma
        q_kPa = q_u / 1000.0
        samples += 1
        if q_kPa > max_Q
            max_Q = q_kPa
            best_B = B
            best_g = g
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("g" => g, "B" => B, "qKPa" => q_kPa))
        end
    end
    status = max_Q < 2000 ? "SUPPORTED" :
             max_Q < 5000 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_Q,
        "secondaryValue"   => best_B,
        "tertiaryValue"    => best_g,
        "reviewValue"      => max_Q,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_Q) && isfinite(best_B) && isfinite(best_g),
        "maxQ"             => max_Q,
        "bestB"            => best_B,
        "bestG"            => best_g,
        "samples"          => samples,
        "gSeed"            => Float64(g_seed),   # §28.6b — JVM hypothesis prose
        "sampleSweep"      => sample_sweep,       # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# Topic 3 — thermalMassTimeLag (line 3169-3206)
# ---------------------------------------------------------------------

"""
    physics_arch_terr_thermal_mass_time_lag(rho_atm_seed, sp) -> Dict
"""
function physics_arch_terr_thermal_mass_time_lag(rho_atm_seed::Real,
                                                  sp::Dict{String,Any})::Dict{String,Any}
    rho = 2400.0
    cp = 880.0
    thickness = 0.2
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_tau = 0.0
    best_thickness = thickness
    best_rho = Float64(rho_atm_seed)
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (t, ρ_atm) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        t_wall = max(0.05, thickness + i * step_size * 0.05)
        rho_atm = max(0.001, Float64(rho_atm_seed) + sin(i * 0.29) * 5.0)
        # √ρ_atm — pure sqrt, bit-identical.
        h = 5.0 * sqrt(rho_atm)
        tau = (rho * cp * t_wall) / max(0.1, h)
        tau_hours = tau / 3600.0
        samples += 1
        if tau_hours > max_tau
            max_tau = tau_hours
            best_thickness = t_wall
            best_rho = rho_atm
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("tWall" => t_wall, "rhoAtm" => rho_atm,
                                                 "tauHours" => tau_hours))
        end
    end
    status = max_tau < 24 ? "SUPPORTED" :
             max_tau < 48 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_tau,
        "secondaryValue"   => best_thickness,
        "tertiaryValue"    => best_rho,
        "reviewValue"      => max_tau,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_tau) && isfinite(best_thickness) && isfinite(best_rho),
        "maxTau"           => max_tau,
        "bestThickness"    => best_thickness,
        "bestRho"          => best_rho,
        "samples"          => samples,
        "rhoAtmSeed"       => Float64(rho_atm_seed),   # §28.6b — JVM hypothesis prose
        "sampleSweep"      => sample_sweep,             # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# Topic 4 (default) — daylightFactor (line 3209-3241)
# ---------------------------------------------------------------------

"""
    physics_arch_terr_daylight_factor(param1, sp) -> Dict
"""
function physics_arch_terr_daylight_factor(param1::Integer,
                                            sp::Dict{String,Any})::Dict{String,Any}
    tau = 0.7
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_DF = 0.0
    best_ratio = 0.0
    best_sky = 0.0
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 (WFR, sky) samples for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        window_to_floor = 0.05 + (Float64(param1) / 99.0) * 0.3 + i * step_size * 0.02
        sky_visibility = 0.3 + i * step_size * 0.1
        DF = window_to_floor * sky_visibility * tau * 100.0
        samples += 1
        if DF > max_DF
            max_DF = DF
            best_ratio = window_to_floor
            best_sky = sky_visibility
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("windowToFloor" => window_to_floor,
                                                 "skyVisibility" => sky_visibility, "DF" => DF))
        end
    end
    status = (max_DF >= 2 && max_DF <= 20) ? "SUPPORTED" :
             max_DF < 2 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_DF,
        "secondaryValue"   => best_ratio,
        "tertiaryValue"    => best_sky,
        "reviewValue"      => max_DF,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_DF) && isfinite(best_ratio) && isfinite(best_sky),
        "maxDF"            => max_DF,
        "bestRatio"        => best_ratio,
        "bestSky"          => best_sky,
        "samples"          => samples,
        "tau"              => tau,
        "param1"           => Int(param1),   # §28.6b — JVM hypothesis prose
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

"""
    physics_compute_arch_terrestrial(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

ARCHITECTURE_TERRESTRIAL dispatch (line 3039). Environment seed
mirrors JVM line 3042-3043: g in [1, 25] m/s², ρ_atm in [0.01, 70]
kg/m³.

Ported (topicIdx) → JVM mapping:
  0 → beamDeflectionSelfWeight(gSeed)
  1 → windLoadPressure(rhoSeed)
  2 → bearingCapacityTerzaghi(gSeed)
  3 → thermalMassTimeLag(rhoSeed)
  4+ → daylightFactor(param1)   (default)
"""
function physics_compute_arch_terrestrial(topic_idx::Integer,
                                           param1::Integer, param2::Real,
                                           sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    g_seed   = 0.5 + (Float64(param1) / 99.0) * 25.0
    rho_seed = 0.01 + (Float64(param2) / 9.0) * 70.0
    if topic_idx == 0
        return physics_arch_terr_beam_deflection_self_weight(g_seed, sp)
    elseif topic_idx == 1
        return physics_arch_terr_wind_load_pressure(rho_seed, sp)
    elseif topic_idx == 2
        return physics_arch_terr_bearing_capacity_terzaghi(g_seed, sp)
    elseif topic_idx == 3
        return physics_arch_terr_thermal_mass_time_lag(rho_seed, sp)
    else
        return physics_arch_terr_daylight_factor(param1, sp)
    end
end
