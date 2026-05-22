# =====================================================================
# src/physics_arch_space.jl — Phase 28 §28.5h — ARCHITECTURE_SPACE
# =====================================================================
#
# Ports of MathPhysicsEngine.computeArchSpace. Vacuum / microgravity
# habitat topics.

# ---------------------------------------------------------------------
# Topic 0 — radiationShieldingMass (line 3457-3488)
# ---------------------------------------------------------------------

"""
    physics_arch_space_radiation_shielding_mass(daily_flux_scale, sp) -> Dict
"""
function physics_arch_space_radiation_shielding_mass(daily_flux_scale::Real,
                                                      sp::Dict{String,Any})::Dict{String,Any}
    E_dep = 5.0
    Dmax = 500.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_sigma = 0.0
    best_phi = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        Phi = 10.0^(2 + Float64(daily_flux_scale) + i * step_size * 0.2)
        sigma = 5.0 + 5.0 * log10(Phi * E_dep / max(1.0, Dmax))
        samples += 1
        if sigma > max_sigma
            max_sigma = sigma
            best_phi = Phi
        end
    end
    status = max_sigma < 100.0 ? "SUPPORTED" :
             max_sigma < 300.0 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_sigma,
        "secondaryValue"   => best_phi,
        "tertiaryValue"    => E_dep,
        "reviewValue"      => max_sigma,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_sigma) && isfinite(best_phi) && isfinite(E_dep),
        "maxSigma"         => max_sigma,
        "bestPhi"          => best_phi,
        "EDep"             => E_dep,
        "Dmax"             => Dmax,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 1 — vacuumThermalBalance (line 3491-3528)
# ---------------------------------------------------------------------
#
# T_eq⁴ = (Q + α·S·A)/(ε·σ·A). pow(.., 0.25) is fractional exponent.

"""
    physics_arch_space_vacuum_thermal_balance(q_internal_seed, sp) -> Dict
"""
function physics_arch_space_vacuum_thermal_balance(q_internal_seed::Real,
                                                    sp::Dict{String,Any})::Dict{String,Any}
    sigma = 5.670374419e-8
    A = 50.0
    S = 1361.0
    alpha = 0.3
    epsilon = 0.85
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_T = 0.0
    min_T = floatmax(Float64)
    best_Q = Float64(q_internal_seed)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        Qint = max(0.0, Float64(q_internal_seed) + i * step_size * 100.0)
        Seff = S * (0.5 + sin(i * 0.31) * 0.4)
        Teq = ((Qint + alpha * Seff * A) / (epsilon * sigma * A))^0.25
        samples += 1
        if Teq > max_T
            max_T = Teq
            best_Q = Qint
        end
        if Teq < min_T
            min_T = Teq
        end
    end
    status = (max_T >= 273 && max_T <= 320) ? "SUPPORTED" :
             (max_T >= 200 && max_T <= 350) ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_T,
        "secondaryValue"   => min_T,
        "tertiaryValue"    => best_Q,
        "reviewValue"      => max_T,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_T) && isfinite(min_T) && isfinite(best_Q),
        "maxT"             => max_T,
        "minT"             => min_T,
        "bestQ"            => best_Q,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 2 — micrometeoroidFlux (line 3531-3561)
# ---------------------------------------------------------------------

"""
    physics_arch_space_micrometeoroid_flux(area_seed, sp) -> Dict
"""
function physics_arch_space_micrometeoroid_flux(area_seed::Real,
                                                 sp::Dict{String,Any})::Dict{String,Any}
    Phi = 1.0e-8
    t_year = 3.156e7
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_impacts = 0.0
    best_area = Float64(area_seed)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        A = max(1.0, Float64(area_seed) + i * step_size * 20.0)
        mission = (1.0 + i * step_size * 0.5) * t_year
        N = Phi * A * mission
        samples += 1
        if N > max_impacts
            max_impacts = N
            best_area = A
        end
    end
    status = max_impacts < 100 ? "SUPPORTED" :
             max_impacts < 1000 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_impacts,
        "secondaryValue"   => best_area,
        "tertiaryValue"    => Phi,
        "reviewValue"      => max_impacts,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_impacts) && isfinite(best_area) && isfinite(Phi),
        "maxImpacts"       => max_impacts,
        "bestArea"         => best_area,
        "Phi"              => Phi,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 3 — centrifugalGravityRadius (line 3564-3600)
# ---------------------------------------------------------------------
#
# a = ω²·R. Find R for a_target given ω. Comfort cap ω ≤ 0.3 rad/s.
# If no comfortable ω found, fall back to ω=0.3 with R = a/0.3².

"""
    physics_arch_space_centrifugal_gravity_radius(a_target, sp) -> Dict
"""
function physics_arch_space_centrifugal_gravity_radius(a_target::Real,
                                                        sp::Dict{String,Any})::Dict{String,Any}
    a_mps = Float64(a_target) * 9.81
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_R = 0.0
    best_omega = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        omega = 0.2 + i * step_size * 0.1
        R = a_mps / (omega * omega)
        samples += 1
        if omega <= 0.3 && R > best_R
            best_R = R
            best_omega = omega
        end
    end
    # JVM line 3581: fallback when no comfortable ω found.
    if best_R == 0 && samples > 0
        best_R = a_mps / (0.3 * 0.3)
        best_omega = 0.3
    end
    status = best_R < 100 ? "SUPPORTED" :
             best_R < 500 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_R,
        "secondaryValue"   => best_omega,
        "tertiaryValue"    => Float64(a_target),
        "reviewValue"      => best_R,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_R) && isfinite(best_omega) && isfinite(Float64(a_target)),
        "bestR"            => best_R,
        "bestOmega"        => best_omega,
        "aTarget"          => Float64(a_target),
        "aMps"             => a_mps,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 4 (default) — orbitalDecayRate (line 3603-3636)
# ---------------------------------------------------------------------
#
# ρ(h) = 1.225·exp(-h/60); decay rate dh/dt = 2π·ρ·B⁻¹·a; days to
# deorbit = h·1000/dh/dt/86400.

"""
    physics_arch_space_orbital_decay_rate(alt_km_seed, sp) -> Dict
"""
function physics_arch_space_orbital_decay_rate(alt_km_seed::Real,
                                                sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_days = 0.0
    best_h = Float64(alt_km_seed)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        h = max(200.0, Float64(alt_km_seed) + i * step_size * 10.0)
        rho = 1.225 * exp(-h / 60.0)
        Binv = 0.001
        a = 7.7e6
        dhdt = 2 * pi * rho * Binv * a
        days_to_deorbit = (h * 1000.0) / max(1e-6, dhdt) / 86400.0
        samples += 1
        if days_to_deorbit > best_days
            best_days = days_to_deorbit
            best_h = h
        end
    end
    status = best_days > 30 ? "SUPPORTED" :
             best_days > 5 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_days,
        "secondaryValue"   => best_h,
        "tertiaryValue"    => 0.001,
        "reviewValue"      => best_days,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_days) && isfinite(best_h),
        "bestDays"         => best_days,
        "bestH"            => best_h,
        "samples"          => samples,
    )
end

"""
    physics_compute_arch_space(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

ARCHITECTURE_SPACE dispatch (line 3445). 5 topics.

Ported (topicIdx) → JVM mapping:
  0 → radiationShieldingMass(0.1 + param2/10)
  1 → vacuumThermalBalance(50 + param1·20)
  2 → micrometeoroidFlux(50 + param1)
  3 → centrifugalGravityRadius(0.5 + param2/9 · 1.5)
  4+ → orbitalDecayRate(200 + param1·5)   (default)
"""
function physics_compute_arch_space(topic_idx::Integer,
                                     param1::Integer, param2::Real,
                                     sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        flux_scale = 0.1 + Float64(param2) / 10.0
        return physics_arch_space_radiation_shielding_mass(flux_scale, sp)
    elseif topic_idx == 1
        Q_seed = 50.0 + Float64(param1) * 20.0
        return physics_arch_space_vacuum_thermal_balance(Q_seed, sp)
    elseif topic_idx == 2
        area_seed = 50.0 + Float64(param1)
        return physics_arch_space_micrometeoroid_flux(area_seed, sp)
    elseif topic_idx == 3
        a_target = 0.5 + Float64(param2) / 9.0 * 1.5
        return physics_arch_space_centrifugal_gravity_radius(a_target, sp)
    else
        alt_seed = 200.0 + Float64(param1) * 5.0
        return physics_arch_space_orbital_decay_rate(alt_seed, sp)
    end
end
