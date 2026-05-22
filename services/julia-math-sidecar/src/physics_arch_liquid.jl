# =====================================================================
# src/physics_arch_liquid.jl — Phase 28 §28.5h — ARCHITECTURE_LIQUID
# =====================================================================
#
# Ports of MathPhysicsEngine.computeArchLiquid. Generic over liquid
# density ρ_liq, viscosity μ, surface tension γ.

# ---------------------------------------------------------------------
# Topic 0 — hydrostaticPressureDepth (line 3272-3303)
# ---------------------------------------------------------------------

"""
    physics_arch_liquid_hydrostatic_pressure_depth(rho_liq, sp) -> Dict
"""
function physics_arch_liquid_hydrostatic_pressure_depth(rho_liq::Real,
                                                         sp::Dict{String,Any})::Dict{String,Any}
    g = 9.81
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_P = 0.0
    best_depth = 0.0
    best_rho = Float64(rho_liq)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        rho = max(100.0, Float64(rho_liq) + sin(i * 0.27) * 500.0)
        depth = 1.0 + i * step_size * 5.0
        P = rho * g * depth
        samples += 1
        if P > max_P
            max_P = P
            best_depth = depth
            best_rho = rho
        end
    end
    physical = max_P < 1.0e8
    status = !physical ? "REFUTED" :
             max_P > 1.0e4 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => max_P,
        "secondaryValue"   => best_depth,
        "tertiaryValue"    => best_rho,
        "reviewValue"      => max_P,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_P) && isfinite(best_depth) && isfinite(best_rho),
        "maxP"             => max_P,
        "bestDepth"        => best_depth,
        "bestRho"          => best_rho,
        "samples"          => samples,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# Topic 1 — buoyantLiftCapacity (line 3306-3334)
# ---------------------------------------------------------------------

"""
    physics_arch_liquid_buoyant_lift_capacity(rho_liq, sp) -> Dict
"""
function physics_arch_liquid_buoyant_lift_capacity(rho_liq::Real,
                                                    sp::Dict{String,Any})::Dict{String,Any}
    g = 9.81
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_F = 0.0
    best_V = 0.0
    best_rho = Float64(rho_liq)
    samples = 0
    for i in 0:(candidate_breadth - 1)
        rho = max(100.0, Float64(rho_liq) + sin(i * 0.27) * 500.0)
        V = 1.0 + i * step_size * 0.5
        F = rho * g * V
        samples += 1
        if F > max_F
            max_F = F
            best_V = V
            best_rho = rho
        end
    end
    status = max_F > 1.0e3 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => max_F,
        "secondaryValue"   => best_V,
        "tertiaryValue"    => best_rho,
        "reviewValue"      => max_F,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_F) && isfinite(best_V) && isfinite(best_rho),
        "maxF"             => max_F,
        "bestV"            => best_V,
        "bestRho"          => best_rho,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 2 — hullBucklingPressure (line 3337-3372)
# ---------------------------------------------------------------------

"""
    physics_arch_liquid_hull_buckling_pressure(rho_liq, sp) -> Dict
"""
function physics_arch_liquid_hull_buckling_pressure(rho_liq::Real,
                                                     sp::Dict{String,Any})::Dict{String,Any}
    E = 200e9
    nu = 0.3
    R = 2.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    min_Pcrit = floatmax(Float64)
    best_t = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        t = 0.005 + i * step_size * 0.002
        # (t/R)³ — integer exponent; expand for clarity / bit-identity proof.
        tR = t / R
        Pcrit = 2.0 * E * (tR * tR * tR) / (1.0 - nu * nu)
        samples += 1
        if Pcrit < min_Pcrit
            min_Pcrit = Pcrit
            best_t = t
        end
    end
    design_depth = 100.0
    design_P = Float64(rho_liq) * 9.81 * design_depth
    safety_margin = min_Pcrit / max(1.0, design_P)
    status = safety_margin > 2.0 ? "SUPPORTED" :
             safety_margin > 1.0 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => min_Pcrit,
        "secondaryValue"   => best_t,
        "tertiaryValue"    => safety_margin,
        "reviewValue"      => min_Pcrit,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(min_Pcrit) && isfinite(best_t) && isfinite(safety_margin),
        "minPcrit"         => min_Pcrit,
        "bestT"            => best_t,
        "safetyMargin"     => safety_margin,
        "designP"          => design_P,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 3 — hydrodynamicDrag (line 3375-3411)
# ---------------------------------------------------------------------
#
# C_d transitions: 24/Re below Re=1 (Stokes), 24/Re + 0.5 in [1, 1e3],
# 0.5 above. Reynolds Re = ρ·v·L/μ.

"""
    physics_arch_liquid_hydrodynamic_drag(rho_liq, mu_mpas, sp) -> Dict
"""
function physics_arch_liquid_hydrodynamic_drag(rho_liq::Real, mu_mpas::Real,
                                                sp::Dict{String,Any})::Dict{String,Any}
    mu = Float64(mu_mpas) * 1e-3
    L = 2.0
    A = L * L
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_F = 0.0
    best_v = 0.0
    best_Cd = 0.0
    best_Re = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        v = 0.1 + i * step_size * 0.5
        Re = Float64(rho_liq) * v * L / max(1e-6, mu)
        Cd = Re < 1.0 ? 24.0 / max(0.01, Re) :
             Re < 1e3 ? 24.0 / Re + 0.5 :
             0.5
        F = 0.5 * Float64(rho_liq) * v * v * A * Cd
        samples += 1
        if F > max_F
            max_F = F
            best_v = v
            best_Cd = Cd
            best_Re = Re
        end
    end
    status = max_F > 1.0 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => max_F,
        "secondaryValue"   => best_v,
        "tertiaryValue"    => best_Cd,
        "reviewValue"      => max_F,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_F) && isfinite(best_v) && isfinite(best_Cd),
        "maxF"             => max_F,
        "bestV"            => best_v,
        "bestCd"           => best_Cd,
        "bestRe"           => best_Re,
        "samples"          => samples,
    )
end

# ---------------------------------------------------------------------
# Topic 4 (default) — dissolvedGasSolubility (line 3414-3441)
# ---------------------------------------------------------------------

"""
    physics_arch_liquid_dissolved_gas_solubility(gamma, rho_liq, sp) -> Dict
"""
function physics_arch_liquid_dissolved_gas_solubility(gamma::Real, rho_liq::Real,
                                                       sp::Dict{String,Any})::Dict{String,Any}
    kH = 0.0008 + Float64(gamma) * 0.05
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_c = 0.0
    best_P = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        P = 0.5 + i * step_size * 0.5
        c = kH * P
        samples += 1
        if c > max_c
            max_c = c
            best_P = P
        end
    end
    status = max_c < 1.0 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => max_c,
        "secondaryValue"   => best_P,
        "tertiaryValue"    => kH,
        "reviewValue"      => max_c,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_c) && isfinite(best_P) && isfinite(kH),
        "maxC"             => max_c,
        "bestP"            => best_P,
        "kH"               => kH,
        "samples"          => samples,
        "gamma"            => Float64(gamma),
        "rhoLiq"           => Float64(rho_liq),
    )
end

"""
    physics_compute_arch_liquid(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

ARCHITECTURE_LIQUID dispatch (line 3254). Liquid-property seeds per
JVM line 3257-3261.

Ported (topicIdx) → JVM mapping:
  0 → hydrostaticPressureDepth(rhoLiqSeed)
  1 → buoyantLiftCapacity(rhoLiqSeed)
  2 → hullBucklingPressure(rhoLiqSeed)
  3 → hydrodynamicDrag(rhoLiqSeed, muSeed)
  4+ → dissolvedGasSolubility(gammaSeed, rhoLiqSeed)   (default)
"""
function physics_compute_arch_liquid(topic_idx::Integer,
                                      param1::Integer, param2::Real,
                                      sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    rho_liq_seed = 200.0 + (Float64(param2) / 9.0) * 13800.0
    # (rho/14000)^0.6 is fractional pow — same case as riemann/basquin.
    gamma_seed = 0.005 + 0.5 * (rho_liq_seed / 14000.0)^0.6
    mu_seed = 0.1 + (Float64(param1) / 99.0) * 9.0
    if topic_idx == 0
        return physics_arch_liquid_hydrostatic_pressure_depth(rho_liq_seed, sp)
    elseif topic_idx == 1
        return physics_arch_liquid_buoyant_lift_capacity(rho_liq_seed, sp)
    elseif topic_idx == 2
        return physics_arch_liquid_hull_buckling_pressure(rho_liq_seed, sp)
    elseif topic_idx == 3
        return physics_arch_liquid_hydrodynamic_drag(rho_liq_seed, mu_seed, sp)
    else
        return physics_arch_liquid_dissolved_gas_solubility(gamma_seed, rho_liq_seed, sp)
    end
end
