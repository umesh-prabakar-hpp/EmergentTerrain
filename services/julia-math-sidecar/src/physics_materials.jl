# =====================================================================
# src/physics_materials.jl — Phase 28 §28.5d — MATERIALS domain
# =====================================================================
#
# Ports of MathPhysicsEngine.computeMaterials topic implementations.
# Same per-domain split convention as physics_mathematics.jl /
# physics_physics.jl: one file owns one JVM domain so each stays
# small as new ports land.
#
# Wire contract / bit-identity discipline match the other domains.
# MATERIALS-specific gotchas:
#   - compositeFailureMode uses Math.min(epsY, epsF) and clamp()
#   - arrhenius uses Math.exp — first exp-heavy port; Math.exp on
#     OpenJDK x86-64 matches Julia's exp on openlibm per the riemann
#     parity proof (which exercises Math.pow → exp(log(...))).
#   - basquin uses Math.pow with fractional exponent — same case
#     as riemann.
#   - sphere packing uses Math.pow(r, 3); explicit r·r·r for bit-
#     identity self-evidence (integer exponent).

# ---------------------------------------------------------------------
# MATERIALS topic 0 — compositeFailureMode (line 1901-1965)
# ---------------------------------------------------------------------
#
# Steel-fibre/polymer composite failure: matrix yields first OR fibre
# fractures first, whichever strain is reached at the lower value.
# Voigt rule σ_C = (Vf·Ef + (1−Vf)·Em)·ε_failure for Vf ∈ {0.1..0.9}.
# Primary = max σ_C across Vf samples; 6-arg ResearchResult passes
# status=SUPPORTED explicitly (JVM line 1964).

"""
    physics_composite_failure_mode_at(sigma_Ym) -> Dict
"""
function physics_composite_failure_mode_at(sigma_Ym::Real)::Dict{String,Any}
    Ef = 200e3
    Em = 3e3
    sigma_F_fracture = 3_000.0  # MPa
    epsY = Float64(sigma_Ym) / Em
    epsF = sigma_F_fracture / Ef
    failure_strain = min(epsY, epsF)
    fibre_first = epsF < epsY
    max_stress = 0.0
    max_Vf = 0.0
    for vf_pct in 10:10:90
        Vf = vf_pct / 100.0
        sigmaC = (Vf * Ef + (1 - Vf) * Em) * failure_strain
        if sigmaC > max_stress
            max_stress = sigmaC
            max_Vf = Vf
        end
    end
    # JVM line 1964: 7-arg ctor (findings, p, s, t, review, status, assessment, hypothesis)
    # → status = SUPPORTED explicitly.
    return Dict{String,Any}(
        "primaryValue"     => max_stress,
        "secondaryValue"   => failure_strain,
        "tertiaryValue"    => max_Vf,
        "reviewValue"      => max_stress,
        "claimStatus"      => "SUPPORTED",
        "theoryConsistent" => isfinite(max_stress) && isfinite(failure_strain) &&
                              isfinite(max_Vf),
        "sigmaYm"          => Float64(sigma_Ym),
        "failureStrain"    => failure_strain,
        "maxVf"            => max_Vf,
        "maxStress"        => max_stress,
        "fibreFirst"       => fibre_first,
        "epsY"             => epsY,
        "epsF"             => epsF,
    )
end

function physics_composite_failure_mode(seed_sigma_Ym::Real, sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        # JVM line 1905: clamp(seedSigmaYm + i*stepSize*50, 10.0, 200.0).
        sigma_Ym = clamp(Float64(seed_sigma_Ym) + i * step_size * 50, 10.0, 200.0)
        r = physics_composite_failure_mode_at(sigma_Ym)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_composite_failure_mode_at(clamp(Float64(seed_sigma_Ym), 10.0, 200.0)) : best
end

# ---------------------------------------------------------------------
# MATERIALS topic 1 — binarySpherePacking (line 1969-1990)
# ---------------------------------------------------------------------
#
# FCC base packing η_FCC = π/(3√2). Sweep r in (0.001, 0.65] with
# `candidateBreadth` samples; for each, compute the added packing from
# octahedral (r ≤ 0.4142) and tetrahedral (r ≤ 0.2247) voids. Track
# the best (peak η). Note: this topic uses candidateBreadth as the
# INNER-resolution sample count, NOT an outer-search loop.

"""
    physics_binary_sphere_packing(sp) -> Dict

No outer/inner split — candidateBreadth controls the r-sample
resolution directly per JVM line 1973. Primary value is the
improvement (bestEta − etaFCC) × 100 in percentage points.
"""
function physics_binary_sphere_packing(sp::Dict{String,Any})::Dict{String,Any}
    eta_FCC = pi / (3 * sqrt(2))
    best_r = 0.0
    best_eta = eta_FCC
    samples = Int(sp["candidateBreadth"])
    sample_sweep = Dict{String,Any}[]   # §28.6b — strided r-samples for JVM sweep string
    for i in 0:(samples - 1)
        # JVM: `r = 0.001 + (i / (double) Math.max(1, samples - 1)) * 0.649`.
        # Match the integer-promotion order exactly.
        r = 0.001 + (i / Float64(max(1, samples - 1))) * 0.649
        # r³ is integer exponent — spell as r·r·r so bit-identity is
        # self-evident vs Math.pow(r, 3) (both reduce to multiply chain).
        r3 = r * r * r
        void_vol_octa  = r <= 0.4142 ? (4.0 / 3) * pi * r3 : 0.0
        void_vol_tetra = r <= 0.2247 ? 2 * (4.0 / 3) * pi * r3 : 0.0
        added_packing = (void_vol_octa + void_vol_tetra) * eta_FCC / ((4.0 / 3) * pi)
        eta_total = min(0.99, eta_FCC + added_packing)
        if eta_total > best_eta
            best_eta = eta_total
            best_r = r
        end
        # JVM line 1981: `i % (samples / Math.min(6, samples)) == 0` (int division).
        if i % (samples ÷ min(6, samples)) == 0
            push!(sample_sweep, Dict{String,Any}("r" => r, "eta" => eta_total))
        end
    end
    improvement = (best_eta - eta_FCC) * 100
    return Dict{String,Any}(
        "primaryValue"     => improvement,
        "secondaryValue"   => best_r,
        "tertiaryValue"    => eta_FCC,
        "reviewValue"      => improvement,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(improvement) && isfinite(best_r) &&
                              isfinite(eta_FCC),
        "etaFCC"           => eta_FCC,
        "bestEta"          => best_eta,
        "bestR"            => best_r,
        "improvement"      => improvement,
        "samples"          => samples,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# MATERIALS topic 2 — compositeStiffnessCrossover (line 1995-2026)
# ---------------------------------------------------------------------
#
# Reuss-bound transverse stiffness E_T = 1/(Vf/Ef + (1−Vf)/Em). For
# each Vf ∈ {0.05..0.95}, find the first Vf where E_T/Em ≥
# targetMultiple. Primary = crossoverVf − 0.5 (departure from 50%).
# Outer search uses a DECREASING ratio per JVM line 2000.

"""
    physics_composite_stiffness_crossover_at(Ef_over_Em, target_multiple) -> Dict
"""
function physics_composite_stiffness_crossover_at(Ef_over_Em::Real,
                                                   target_multiple::Real)::Dict{String,Any}
    Em = 3e3
    Ef = Float64(Ef_over_Em) * Em
    crossover_Vf = -1.0
    for vf_pct in 5:5:95
        Vf = vf_pct / 100.0
        ET = 1.0 / (Vf / Ef + (1 - Vf) / Em)
        multiple = ET / Em
        if multiple >= Float64(target_multiple) && crossover_Vf < 0
            crossover_Vf = Vf
        end
    end
    if crossover_Vf < 0
        crossover_Vf = 1.0
    end
    surprise = crossover_Vf - 0.5
    return Dict{String,Any}(
        "primaryValue"     => surprise,
        "secondaryValue"   => crossover_Vf,
        "tertiaryValue"    => Float64(Ef_over_Em),
        "reviewValue"      => surprise,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(surprise) && isfinite(crossover_Vf) &&
                              isfinite(Float64(Ef_over_Em)),
        "EfOverEm"         => Float64(Ef_over_Em),
        "crossoverVf"      => crossover_Vf,
        "surprise"         => surprise,
        "targetMultiple"   => Float64(target_multiple),
    )
end

function physics_composite_stiffness_crossover(seed_ratio::Real, target_multiple::Real,
                                                sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        # JVM line 2000: `max(2.0, seedRatio - i * stepSize * 5)`.
        ratio = max(2.0, Float64(seed_ratio) - i * step_size * 5.0)
        r = physics_composite_stiffness_crossover_at(ratio, target_multiple)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_composite_stiffness_crossover_at(seed_ratio, target_multiple) : best
end

# ---------------------------------------------------------------------
# MATERIALS topic 3 — arrheniusRuleValidity (line 2030-2077)
# ---------------------------------------------------------------------
#
# Van't Hoff rule of thumb: diffusion ~doubles per 10 K. For each
# Ea ∈ 10..maxEaKJ kJ/mol, compute ratio D₂/D₁ = exp(-Ea/R·(1/(T+10)
# − 1/T)·1000); test |ratio − 2|/2 < 10%. Status combines limited-
# range + max-exceeds-2.2 criteria per JVM line 2064-2065.
#
# First port to use Math.exp directly; bit-identity argument relies
# on the same OpenJDK/openlibm agreement that powers Math.pow.

"""
    physics_arrhenius_rule_validity_at(T, max_Ea_kJ) -> Dict
"""
function physics_arrhenius_rule_validity_at(T::Real, max_Ea_kJ::Real)::Dict{String,Any}
    R = 8.314
    valid = 0
    total = 0
    first_valid = -1.0
    last_valid = -1.0
    worst_dev = 0.0
    max_ratio = 0.0
    # JVM line 2048: `for (int ea = 10; ea <= (int) maxEaKJ; ea += 10)`.
    # `(int) maxEaKJ` truncates toward 0; Julia's trunc(Int, ...) matches.
    ea_upper = trunc(Int, Float64(max_Ea_kJ))
    for ea in 10:10:ea_upper
        Ea = Float64(ea) * 1000.0
        D1 = exp(-Ea / (R * Float64(T)))
        D2 = exp(-Ea / (R * (Float64(T) + 10)))
        ratio = D2 / D1
        if ratio > max_ratio
            max_ratio = ratio
        end
        dev = abs(ratio - 2.0)
        if dev > worst_dev
            worst_dev = dev
        end
        holds = dev / 2.0 < 0.10
        total += 1
        if holds
            valid += 1
            if first_valid < 0
                first_valid = Float64(ea)
            end
            last_valid = Float64(ea)
        end
    end
    limited_range = valid > 0 && valid < total
    high_Ea_far_exceeds = max_ratio >= 2.2
    status = (limited_range && high_Ea_far_exceeds) ? "SUPPORTED" :
             (limited_range || high_Ea_far_exceeds) ? "PARTIAL" : "REFUTED"
    # JVM line 2071-2076: 7-arg ResearchResult — explicit status.
    # primary=worstDev, secondary=firstValid (or maxEaKJ if no valid),
    # tertiary=lastValid (or maxEaKJ), review=worstDev.
    secondary = first_valid > 0 ? first_valid : Float64(max_Ea_kJ)
    tertiary  = last_valid  > 0 ? last_valid  : Float64(max_Ea_kJ)
    return Dict{String,Any}(
        "primaryValue"     => worst_dev,
        "secondaryValue"   => secondary,
        "tertiaryValue"    => tertiary,
        "reviewValue"      => worst_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(worst_dev) && isfinite(secondary) &&
                              isfinite(tertiary),
        "T"                => Float64(T),
        "maxEaKJ"          => Float64(max_Ea_kJ),
        "worstDev"         => worst_dev,
        "maxRatio"         => max_ratio,
        "valid"            => valid,
        "total"            => total,
        "firstValid"       => first_valid,
        "lastValid"        => last_valid,
        "limitedRange"     => limited_range,
        "highEaFarExceeds" => high_Ea_far_exceeds,
    )
end

function physics_arrhenius_rule_validity(seed_T::Real, max_Ea_kJ::Real,
                                          sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        T = max(200.0, Float64(seed_T) + i * step_size * 20.0)
        r = physics_arrhenius_rule_validity_at(T, max_Ea_kJ)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_arrhenius_rule_validity_at(seed_T, max_Ea_kJ) : best
end

# ---------------------------------------------------------------------
# MATERIALS topic 4 — thermalStressSafety (line 2081-2113)
# ---------------------------------------------------------------------
#
# σ = α·E·ΔT for thermal stress. E = 200 GPa, 9 fixed coefficients of
# thermal expansion {1, 5, 10, 12, 17, 23, 25, 30, 50} µm/(m·K).
# Primary = fraction of materials exceeding yield σ_Y.

# JVM line 2096-2097 — fixed material table; keep order + values byte-
# for-byte (the JVM caller composes the label-prose locally so we only
# need the α-array order).
const THERMAL_ALPHAS = (1.0, 5.0, 10.0, 12.0, 17.0, 23.0, 25.0, 30.0, 50.0)

"""
    physics_thermal_stress_safety_at(delta_T, sigma_Y_MPa) -> Dict
"""
function physics_thermal_stress_safety_at(delta_T::Real, sigma_Y_MPa::Real)::Dict{String,Any}
    E = 200e3
    crit_alpha = Float64(sigma_Y_MPa) / (E * Float64(delta_T)) * 1e6
    safe = 0
    for alpha in THERMAL_ALPHAS
        stress = alpha * 1e-6 * E * Float64(delta_T)
        if stress < Float64(sigma_Y_MPa)
            safe += 1
        end
    end
    n_alphas = length(THERMAL_ALPHAS)
    fail_fraction = Float64(n_alphas - safe) / n_alphas
    # JVM line 2107-2110: 4-arg ResearchResult — status=OBSERVATIONAL.
    return Dict{String,Any}(
        "primaryValue"     => fail_fraction,
        "secondaryValue"   => crit_alpha,
        "tertiaryValue"    => Float64(safe),
        "reviewValue"      => fail_fraction,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(fail_fraction) && isfinite(crit_alpha),
        "deltaT"           => Float64(delta_T),
        "sigmaYMPa"        => Float64(sigma_Y_MPa),
        "critAlpha"        => crit_alpha,
        "safe"             => safe,
        "nAlphas"          => n_alphas,
        "failFraction"     => fail_fraction,
    )
end

function physics_thermal_stress_safety(seed_delta_T::Real, sigma_Y_MPa::Real,
                                        sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        delta_T = max(10.0, Float64(seed_delta_T) + i * step_size * 20.0)
        r = physics_thermal_stress_safety_at(delta_T, sigma_Y_MPa)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_thermal_stress_safety_at(seed_delta_T, sigma_Y_MPa) : best
end

# ---------------------------------------------------------------------
# MATERIALS topic 5 — hallPetchStrengthening (line 1691-1723)
# ---------------------------------------------------------------------
#
# Hall-Petch: σ_y = σ_0 + k·d^(−1/2). Sweep grain size d_um downward
# from 50 µm; track max yield. Status based on physical ceiling
# (5 GPa) and 2× rule.

"""
    physics_hall_petch_strengthening(seed_sigma0, param2, sp) -> Dict

Single-pass (no inner/outer split) — sweeps grain size d directly
in the candidateBreadth loop per JVM line 1691-1723.
"""
function physics_hall_petch_strengthening(seed_sigma0::Real, param2::Real,
                                           sp::Dict{String,Any})::Dict{String,Any}
    k = 300.0 + Float64(param2) * 50.0
    sigma0 = max(50.0, Float64(seed_sigma0))
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_yield = 0.0
    best_d = 0.0
    samples = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 grain sizes for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        d_um = max(0.05, 50.0 - i * step_size * 1.5)
        sigma_Y = sigma0 + k / sqrt(d_um)
        samples += 1
        if sigma_Y > max_yield
            max_yield = sigma_Y
            best_d = d_um
        end
        if i < 4
            push!(sample_sweep, Dict{String,Any}("d" => d_um, "sigmaY" => sigma_Y))
        end
    end
    physical_ceiling = max_yield < 5000
    status = physical_ceiling ?
        (max_yield > sigma0 * 2 ? "SUPPORTED" : "PARTIAL") : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => max_yield,
        "secondaryValue"   => sigma0,
        "tertiaryValue"    => k,
        "reviewValue"      => max_yield,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_yield) && isfinite(sigma0) && isfinite(k),
        "sigma0"           => sigma0,
        "k"                => k,
        "maxYield"         => max_yield,
        "bestD"            => best_d,
        "samples"          => samples,
        "physicalCeiling"  => physical_ceiling,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# MATERIALS topic 6 — braggDiffraction (line 1726-1767)
# ---------------------------------------------------------------------
#
# Bragg n·λ = 2·d·sin θ. For each d in the sweep, count orders 1..3
# with sin θ ≤ 1; record the best first-order θ and mean order count.

"""
    physics_bragg_diffraction(seed_d_nm, lambda_nm, sp) -> Dict
"""
function physics_bragg_diffraction(seed_d_nm::Real, lambda_nm::Real,
                                    sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_angle = 0.0
    best_d = Float64(seed_d_nm)
    best_n = 1
    samples = 0
    valid_orders = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — first ≤4 lattice spacings for JVM sweep string
    for i in 0:(candidate_breadth - 1)
        d = max(0.05, Float64(seed_d_nm) + i * step_size * 0.02)
        orders = 0
        for n in 1:3
            sin_theta = (n * Float64(lambda_nm)) / (2 * d)
            if sin_theta <= 1.0
                theta = rad2deg(asin(sin_theta))
                if n == 1 && theta > best_angle
                    best_angle = theta
                    best_d = d
                    best_n = n
                end
                orders += 1
            end
        end
        valid_orders += orders
        samples += 1
        if i < 4
            # JVM line 2012-2014: first-order θ at this d (90° when sin θ > 1).
            sin_t1 = Float64(lambda_nm) / (2 * d)
            t1 = sin_t1 <= 1.0 ? rad2deg(asin(sin_t1)) : 90.0
            push!(sample_sweep, Dict{String,Any}("d" => d, "t1" => t1))
        end
    end
    mean_orders = samples > 0 ? Float64(valid_orders) / samples : 0.0
    status = (best_angle > 0.5 && best_angle < 89.5) ? "SUPPORTED" :
             best_angle > 0 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_angle,
        "secondaryValue"   => best_d,
        "tertiaryValue"    => mean_orders,
        "reviewValue"      => best_angle,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_angle) && isfinite(best_d) &&
                              isfinite(mean_orders),
        "bestAngle"        => best_angle,
        "bestD"            => best_d,
        "bestN"            => best_n,
        "meanOrders"       => mean_orders,
        "validOrders"      => valid_orders,
        "samples"          => samples,
        "lambdaNm"         => Float64(lambda_nm),
        "seedDnm"          => Float64(seed_d_nm),   # §28.6b — JVM hypothesis prose needs it
        "sampleSweep"      => sample_sweep,          # §28.6b — JVM rebuilds the sweep prose
    )
end

# ---------------------------------------------------------------------
# MATERIALS topic 7 — carnotEfficiency (line 1770-1804)
# ---------------------------------------------------------------------
#
# η = 1 − Tc/Th. Each sample perturbs Tc with sin(i·0.31)·30; the
# Th step is i·stepSize·25 above the seed (with the floor clamping
# Th ≥ Tc + 10).

"""
    physics_carnot_efficiency(seed_Tc, seed_Th, sp) -> Dict
"""
function physics_carnot_efficiency(seed_Tc::Real, seed_Th::Real,
                                    sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_eta = 0.0
    best_Tc = Float64(seed_Tc)
    best_Th = Float64(seed_Th)
    samples = 0
    sum_eta = 0.0
    for i in 0:(candidate_breadth - 1)
        Tc = max(50.0, Float64(seed_Tc) + sin(i * 0.31) * 30.0)
        Th = max(Tc + 10.0, Float64(seed_Th) + i * step_size * 25.0)
        eta = 1.0 - Tc / Th
        sum_eta += eta
        samples += 1
        if eta > best_eta
            best_eta = eta
            best_Tc = Tc
            best_Th = Th
        end
    end
    mean_eta = samples > 0 ? sum_eta / samples : 0.0
    physical = best_eta >= 0 && best_eta < 1.0
    status = !physical ? "REFUTED" :
             best_eta > 0.5 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_eta,
        "secondaryValue"   => mean_eta,
        "tertiaryValue"    => best_Th,
        "reviewValue"      => best_eta,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_eta) && isfinite(mean_eta) &&
                              isfinite(best_Th),
        "bestEta"          => best_eta,
        "bestTc"           => best_Tc,
        "bestTh"           => best_Th,
        "meanEta"          => mean_eta,
        "samples"          => samples,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# MATERIALS topic 8 — basquinFatigue (line 1807-1838)
# ---------------------------------------------------------------------
#
# Basquin S-N: σ_a = σ'_f·(2·N_f)^b with b in [-0.15, -0.05]. Sweep
# N_f upward; record max σ_a. Status flags when σ_a > UTS = σ'_f·0.9.
# Math.pow(2*Nf, b) is the fractional-exponent case — same as riemann.

"""
    physics_basquin_fatigue(seed_sigma_f, param2, sp) -> Dict
"""
function physics_basquin_fatigue(seed_sigma_f::Real, param2::Real,
                                  sp::Dict{String,Any})::Dict{String,Any}
    sigma_prime_F = Float64(seed_sigma_f)
    b = -0.08 - Float64(param2) * 0.01
    UTS = sigma_prime_F * 0.9
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_amp = 0.0
    best_Nf = Int64(0)
    count_above_uts = 0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        # JVM line 1817: `(long) Math.max(1_000, 10_000 + i*stepSize*50_000)`.
        # Java cast to long is truncation toward 0 for positive doubles;
        # match with Julia floor(Int64, ...) for the same semantic.
        Nf_raw = max(1_000.0, 10_000.0 + i * step_size * 50_000.0)
        Nf = trunc(Int64, Nf_raw)
        # JVM: Math.pow(2.0 * Nf, b) — fractional exponent, same case
        # as riemann's Math.pow(n, -s).
        sigma_a = sigma_prime_F * (2.0 * Float64(Nf))^b
        samples += 1
        if sigma_a > best_amp
            best_amp = sigma_a
            best_Nf = Nf
        end
        if sigma_a > UTS
            count_above_uts += 1
        end
    end
    physical = count_above_uts == 0
    status = !physical ? "REFUTED" :
             best_amp > sigma_prime_F * 0.5 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_amp,
        "secondaryValue"   => sigma_prime_F,
        "tertiaryValue"    => abs(b),
        "reviewValue"      => best_amp,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_amp) && isfinite(sigma_prime_F) &&
                              isfinite(abs(b)),
        "sigmaPrimeF"      => sigma_prime_F,
        "b"                => b,
        "UTS"              => UTS,
        "bestAmp"          => best_amp,
        "bestNf"           => best_Nf,
        "countAboveUts"    => count_above_uts,
        "samples"          => samples,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# MATERIALS topic 9 (default) — youngLaplaceCapillary (line 1842-1878)
# ---------------------------------------------------------------------
#
# h = 2γ·cos θ / (ρ·g·r). Sweep radius DOWNWARD from seed; track max
# rise in cm. Status flags when most samples exceed 50 cm (sub-µm
# regime where other physics dominate).

"""
    physics_young_laplace_capillary(seed_radius_mm, sp) -> Dict
"""
function physics_young_laplace_capillary(seed_radius_mm::Real,
                                          sp::Dict{String,Any})::Dict{String,Any}
    gamma = 0.0728   # N/m
    rho = 1000.0     # kg/m³
    g = 9.81
    contact_angle = 20.0 * DEGREES_TO_RADIANS  # wet glass, 20° → rad
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    max_h = 0.0
    best_r = Float64(seed_radius_mm)
    samples = 0
    count_above_limit = 0
    for i in 0:(candidate_breadth - 1)
        r_mm = max(0.005, Float64(seed_radius_mm) - i * step_size * 0.005)
        r_m  = r_mm * 1e-3
        h_m  = (2 * gamma * cos(contact_angle)) / (rho * g * r_m)
        h_cm = h_m * 100.0
        samples += 1
        if h_cm > max_h
            max_h = h_cm
            best_r = r_mm
        end
        if h_cm > 50.0
            count_above_limit += 1
        end
    end
    physical = count_above_limit < samples ÷ 2
    status = !physical ? "REFUTED" :
             max_h > 5.0 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => max_h,
        "secondaryValue"   => best_r,
        "tertiaryValue"    => gamma,
        "reviewValue"      => max_h,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(max_h) && isfinite(best_r) && isfinite(gamma),
        "maxH"             => max_h,
        "bestR"            => best_r,
        "gamma"            => gamma,
        "samples"          => samples,
        "countAboveLimit"  => count_above_limit,
        "physical"         => physical,
    )
end

"""
    physics_compute_materials(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

MATERIALS-domain dispatch. Mirrors JVM computeMaterials (line 1882).

Ported (topicIdx) → JVM mapping:
  0 → physics_composite_failure_mode(50.0 + param1·5)
  1 → physics_binary_sphere_packing()
  2 → physics_composite_stiffness_crossover(10.0 + param1·2, 2.0)
  3 → physics_arrhenius_rule_validity(300.0 + param1·3, 50.0 + param2·20)
  4 → physics_thermal_stress_safety(50.0 + param1·3, 200.0 + param2·50)
  5 → physics_hall_petch_strengthening(50.0 + param1, param2)
  6 → physics_bragg_diffraction(0.05 + param1/500.0, 0.154)
  7 → physics_carnot_efficiency(280.0 + param2·10, 400.0 + param1·5)
  8 → physics_basquin_fatigue(800.0 + param1·5, param2)
  9+ → physics_young_laplace_capillary(0.1 + param1/200.0)   (default branch)
"""
function physics_compute_materials(topic_idx::Integer,
                                    param1::Integer, param2::Real,
                                    sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        seed = 50.0 + Float64(param1) * 5.0
        return physics_composite_failure_mode(seed, sp)
    elseif topic_idx == 1
        return physics_binary_sphere_packing(sp)
    elseif topic_idx == 2
        seed = 10.0 + Float64(param1) * 2.0
        return physics_composite_stiffness_crossover(seed, 2.0, sp)
    elseif topic_idx == 3
        seedT = 300.0 + Float64(param1) * 3.0
        maxEa = 50.0 + Float64(param2) * 20.0
        return physics_arrhenius_rule_validity(seedT, maxEa, sp)
    elseif topic_idx == 4
        seedDT = 50.0 + Float64(param1) * 3.0
        sigmaY = 200.0 + Float64(param2) * 50.0
        return physics_thermal_stress_safety(seedDT, sigmaY, sp)
    elseif topic_idx == 5
        seedS0 = 50.0 + Float64(param1)
        return physics_hall_petch_strengthening(seedS0, param2, sp)
    elseif topic_idx == 6
        seedD = 0.05 + Float64(param1) / 500.0
        return physics_bragg_diffraction(seedD, 0.154, sp)
    elseif topic_idx == 7
        seedTc = 280.0 + Float64(param2) * 10.0
        seedTh = 400.0 + Float64(param1) * 5.0
        return physics_carnot_efficiency(seedTc, seedTh, sp)
    elseif topic_idx == 8
        seedSF = 800.0 + Float64(param1) * 5.0
        return physics_basquin_fatigue(seedSF, param2, sp)
    else
        # Topic 9+ — JVM `default` branch routes to youngLaplaceCapillary.
        seedR = 0.1 + Float64(param1) / 200.0
        return physics_young_laplace_capillary(seedR, sp)
    end
end
