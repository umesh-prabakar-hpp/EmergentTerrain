# =====================================================================
# src/physics_interdisc.jl — Phase 28 §28.5e — INTERDISCIPLINARY domain
# =====================================================================
#
# Ports of MathPhysicsEngine.computeInterdisciplinary topics. Each
# topic chains a number-theoretic or physics primitive into a
# materials/circuit/optics secondary computation — the "cross-domain"
# bridge is the topic's actual content, not just narration.
#
# Wire contract + bit-identity discipline match the other per-domain
# files. Notable per-topic gotchas captured inline in the docstrings.

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 0 — collatzRlcDepthResonance (line 2250-2280)
# ---------------------------------------------------------------------
#
# Math→Physics chain: Collatz stopping depth at n = param1*3 + i*step*10
# drives inductance L = depth/10000 (H); RLC resonance f₀ =
# 1/(2π·√(L·C)). Best = highest f₀ across the sweep.

"""
    physics_interdisc_collatz_rlc_depth_resonance(param1, param2, sp) -> Dict
"""
function physics_interdisc_collatz_rlc_depth_resonance(param1::Integer, param2::Real,
                                                        sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_f0 = 0.0
    best_depth = 0
    best_n = Int(param1) * 3
    for i in 0:(candidate_breadth - 1)
        n = max(2, Int(param1) * 3 + Int(floor(i * step_size * 10)))
        depth = collatz_steps(n)
        L = max(1e-5, Float64(depth) / 10000.0)
        C = 1e-6 + Float64(param2) * 1e-7
        f0 = 1.0 / (2 * pi * sqrt(L * C))
        if f0 > best_f0
            best_f0 = f0
            best_depth = depth
            best_n = n
        end
    end
    L_best = max(1e-5, Float64(best_depth) / 10000.0)
    C = 1e-6 + Float64(param2) * 1e-7
    crit_R = 2 * sqrt(L_best / C)
    deep_means_low = best_depth > 50 && best_f0 < 5000
    status = deep_means_low ? "SUPPORTED" : "PARTIAL"
    # JVM line 2274-2277: 6-arg ResearchResult — explicit status.
    return Dict{String,Any}(
        "primaryValue"     => best_f0,
        "secondaryValue"   => Float64(best_depth),
        "tertiaryValue"    => crit_R,
        "reviewValue"      => best_f0,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_f0) && isfinite(Float64(best_depth)) &&
                              isfinite(crit_R),
        "bestF0"           => best_f0,
        "bestDepth"        => best_depth,
        "bestN"            => best_n,
        "bestL"            => L_best,
        "critR"            => crit_R,
        "C"                => C,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 1 — primeGapDefectThermal (line 2286-2321)
# ---------------------------------------------------------------------
#
# Math→Materials chain: max prime gap in [start, start+60) → defect
# spacing in µm; effective CTE α = 5 + 20/gap; thermal stress σ = α·E·ΔT.
# Best = highest stress across the outer sweep.

"""
    physics_interdisc_prime_gap_defect_thermal(param1, param2, sp) -> Dict
"""
function physics_interdisc_prime_gap_defect_thermal(param1::Integer, param2::Real,
                                                     sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_stress = 0.0
    best_gap = 0
    best_start = Int(param1) * 10
    for i in 0:(candidate_breadth - 1)
        start = max(2, Int(param1) * 10 + Int(floor(i * step_size * 50)))
        end_  = start + 60
        max_gap = 2
        prev = -1
        for n in start:(end_ - 1)
            if is_prime(n)
                if prev > 0 && (n - prev) > max_gap
                    max_gap = n - prev
                end
                prev = n
            end
        end
        alpha = 5.0 + 20.0 / max_gap
        delta_T = 200.0 + Float64(param2) * 30.0
        E = 200e3
        stress = alpha * 1e-6 * E * delta_T
        if stress > best_stress
            best_stress = stress
            best_gap = max_gap
            best_start = start
        end
    end
    threshold = 200.0 + Float64(param2) * 30.0
    yield_exceeded = best_stress > threshold
    status = yield_exceeded ? "SUPPORTED" : "PARTIAL"
    best_alpha = 5.0 + 20.0 / best_gap
    return Dict{String,Any}(
        "primaryValue"     => best_stress,
        "secondaryValue"   => Float64(best_gap),
        "tertiaryValue"    => best_alpha,
        "reviewValue"      => best_stress,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_stress) && isfinite(Float64(best_gap)) &&
                              isfinite(best_alpha),
        "bestStress"       => best_stress,
        "bestGap"          => best_gap,
        "bestStart"        => best_start,
        "bestAlpha"        => best_alpha,
        "threshold"        => threshold,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 2 — resonantThermalFatigue (line 2328-2370)
# ---------------------------------------------------------------------
#
# Physics→Materials chain: RLC resonance frequency f₀ → thermal cycling
# rate ΔT = 10 + f₀/1000; stress σ = α·E·ΔT for each of 9 fixed CTEs;
# count how many fail σ > 200 MPa. Best = highest fail fraction.
# Uses NEGATIVE_INFINITY sentinel so a 0/9 fail fraction is a valid
# null result (JVM line 2332).

"""
    physics_interdisc_resonant_thermal_fatigue(param1, param2, sp) -> Dict
"""
function physics_interdisc_resonant_thermal_fatigue(param1::Integer, param2::Real,
                                                     sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    # JVM: -Double.NEGATIVE_INFINITY sentinel so a zero-failure outcome
    # still wins on the first candidate.
    best_fatigue_frac = -Inf
    best_f0 = 0.0
    best_L = 0.0
    for i in 0:(candidate_breadth - 1)
        L = max(1e-5, (0.001 + Float64(param1) / 10000.0) /
                       (1.0 + i * step_size * 0.2))
        C = 1e-6 + Float64(param2) * 1e-7
        f0 = 1.0 / (2 * pi * sqrt(L * C))
        delta_T = 10.0 + f0 / 1000.0
        E = 200e3
        failed = 0
        for alpha in THERMAL_ALPHAS
            stress = alpha * 1e-6 * E * delta_T
            if stress > 200
                failed += 1
            end
        end
        fatigue_frac = Float64(failed) / length(THERMAL_ALPHAS)
        if fatigue_frac > best_fatigue_frac
            best_fatigue_frac = fatigue_frac
            best_f0 = f0
            best_L = L
        end
    end
    if best_fatigue_frac < 0
        best_fatigue_frac = 0.0
    end
    critical_fatigue = best_fatigue_frac >= 0.5
    status = critical_fatigue ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_fatigue_frac,
        "secondaryValue"   => best_f0,
        "tertiaryValue"    => best_L,
        "reviewValue"      => best_fatigue_frac,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_fatigue_frac) && isfinite(best_f0) &&
                              isfinite(best_L),
        "bestFatigueFrac"  => best_fatigue_frac,
        "bestF0"           => best_f0,
        "bestL"            => best_L,
        "criticalFatigue"  => critical_fatigue,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 3 — happyPackingAnomaly (line 2377-2411)
# ---------------------------------------------------------------------
#
# Math→Materials chain: happy-number density in window → small-sphere
# radius r = min(0.45, 0.2 + 0.3·density/0.143); octahedral void
# improvement over η_FCC. Best = highest improvement.

"""
    physics_interdisc_happy_packing_anomaly(param1, param2, sp) -> Dict
"""
function physics_interdisc_happy_packing_anomaly(param1::Integer, param2::Real,
                                                  sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_improvement = 0.0
    best_density = 0.143
    best_start = Int(param1) * 7
    eta_FCC = pi / (3 * sqrt(2))
    for i in 0:(candidate_breadth - 1)
        start = max(1, Int(param1) * 7 + Int(floor(i * step_size * 200)))
        count = 80 + Int(floor(Float64(param2) * 20))
        happy = 0
        for n in start:(start + count - 1)
            if is_happy(n)
                happy += 1
            end
        end
        density = Float64(happy) / count
        r = min(0.45, 0.2 + 0.3 * (density / 0.143))
        # r³ is integer exponent — spell as r·r·r for bit-identity.
        r3 = r * r * r
        void_vol = r <= 0.4142 ? (4.0 / 3) * pi * r3 : 0.0
        improvement = (void_vol * eta_FCC / ((4.0 / 3) * pi)) * 100
        if improvement > best_improvement
            best_improvement = improvement
            best_density = density
            best_start = start
        end
    end
    r_best = min(0.45, 0.2 + 0.3 * (best_density / 0.143))
    anomalous = best_density > 0.155 || best_density < 0.130
    status = anomalous ? "SUPPORTED" : "OBSERVATIONAL"
    # JVM line 2405-2408: primary = bestImprovement/100, secondary = bestDensity,
    # tertiary = r_best, review = bestImprovement/100.
    return Dict{String,Any}(
        "primaryValue"     => best_improvement / 100.0,
        "secondaryValue"   => best_density,
        "tertiaryValue"    => r_best,
        "reviewValue"      => best_improvement / 100.0,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_improvement) && isfinite(best_density) &&
                              isfinite(r_best),
        "bestImprovement"  => best_improvement,
        "bestDensity"      => best_density,
        "bestStart"        => best_start,
        "rBest"            => r_best,
        "etaFCC"           => eta_FCC,
        "anomalous"        => anomalous,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 4 — vanDerWaalsCompositeStress (line 2417-2458)
# ---------------------------------------------------------------------
#
# Physics→Materials chain: VdW crossover pressure (≥1% deviation from
# ideal-gas) at temperature T → mechanical loading on a fibre composite;
# σ = P_c · E_L / 1000 where E_L = Vf·Ef + (1−Vf)·Em.
#
# NOTE: the inner Newton loop here is the same shape as MATERIALS topic 2,
# BUT with `for iter in 1:20` (not 30) and a `break` after the first
# crossover is found. Mirror exactly.

"""
    physics_interdisc_van_der_waals_composite_stress(param1, param2, sp) -> Dict
"""
function physics_interdisc_van_der_waals_composite_stress(param1::Integer, param2::Real,
                                                           sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_stress = 0.0
    best_crossover_P = -1.0
    best_T = 0.0
    for i in 0:(candidate_breadth - 1)
        T = max(150.0, 200.0 + Float64(param1) * 3.0 + i * step_size * 20.0)
        a = 3.640
        b = 0.04267
        R = 0.08206
        crossover_P = -1.0
        for p in 1:100
            P = Float64(p)
            V_ideal = R * T / P
            V = V_ideal
            for _ in 1:20  # JVM line 2428: only 20 iters here (vs 30 in MATERIALS).
                f  = (P + a / (V * V)) * (V - b) - R * T
                df = (P + a / (V * V)) - 2 * a * (V - b) / (V * V * V)
                if abs(df) < 1e-15
                    break
                end
                V -= f / df
                if V <= b
                    V = b * 1.01
                    break
                end
            end
            dev = abs(V - V_ideal) / V_ideal
            # JVM line 2436: `break` after first crossover — find lowest p
            # only, not max-deviation.
            if dev > 0.01 && crossover_P < 0
                crossover_P = Float64(p)
                break
            end
        end
        Ef = 200e3
        Em = 3e3
        Vf = 0.3 + Float64(param2) * 0.05
        EL = Vf * Ef + (1 - Vf) * Em
        stress = crossover_P > 0 ? crossover_P * EL / 1000.0 : 0.0
        if stress > best_stress
            best_stress = stress
            best_crossover_P = crossover_P
            best_T = T
        end
    end
    high_stress = best_stress > 3000
    status = high_stress ? "SUPPORTED" : "OBSERVATIONAL"
    # JVM line 2452-2455: 6-arg ResearchResult.
    secondary = best_crossover_P > 0 ? best_crossover_P : 0.0
    return Dict{String,Any}(
        "primaryValue"     => best_stress,
        "secondaryValue"   => secondary,
        "tertiaryValue"    => best_T,
        "reviewValue"      => best_stress,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_stress) && isfinite(secondary) &&
                              isfinite(best_T),
        "bestStress"       => best_stress,
        "bestCrossoverP"   => best_crossover_P,
        "bestT"            => best_T,
        "highStress"       => high_stress,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 5 — hallEffectCarrierSign (line 2467-2507)
# ---------------------------------------------------------------------
#
# Physics→Materials chain: V_H = (I·B)/(n·e·t) for a Hall bar. Sign
# recovery rate over varied (B, n); F3 spec bound |V_H| ≤ 1 V.
# trueSign picked from `((int)(param2*10)) % 2`: even → +1 (holes),
# odd → -1 (electrons). JVM uses Math.signum, which returns -1/0/+1.

"""
    physics_interdisc_hall_effect_carrier_sign(param1, param2, sp) -> Dict
"""
function physics_interdisc_hall_effect_carrier_sign(param1::Integer, param2::Real,
                                                     sp::Dict{String,Any})::Dict{String,Any}
    e = 1.602176634e-19
    I = 0.1
    t = 1e-4
    # JVM line 2474: `((int)(param2 * 10)) % 2 == 0 ? +1 : -1`.
    # Java `(int)(...)` truncates toward 0; Julia trunc(Int, ...) matches.
    true_sign = (trunc(Int, Float64(param2) * 10) % 2 == 0) ? 1.0 : -1.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_abs_V = 0.0
    best_B = 0.0
    best_n = 0.0
    correct_sign = 0
    total_samples = 0
    for i in 0:(candidate_breadth - 1)
        B = 0.05 + (Float64(param1) + i * step_size * 5) * 0.002
        n = 1e24 + i * step_size * 1e23
        V_H = true_sign * (I * B) / (n * e * t)
        total_samples += 1
        # JVM uses Math.signum — Julia's `sign` returns 0.0 for 0.0 which
        # would never match here (V_H non-zero); match exactly for safety.
        if sign(V_H) == true_sign
            correct_sign += 1
        end
        if abs(V_H) > best_abs_V
            best_abs_V = abs(V_H)
            best_B = B
            best_n = n
        end
    end
    sign_recovery = Float64(correct_sign) / max(1, total_samples)
    physical = best_abs_V <= 1.0
    status = (physical && sign_recovery >= 0.95) ? "SUPPORTED" :
             physical ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_abs_V,
        "secondaryValue"   => sign_recovery,
        "tertiaryValue"    => best_n,
        "reviewValue"      => best_abs_V,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_abs_V) && isfinite(sign_recovery) &&
                              isfinite(best_n),
        "bestAbsV"         => best_abs_V,
        "bestB"            => best_B,
        "bestN"            => best_n,
        "correctSign"      => correct_sign,
        "totalSamples"     => total_samples,
        "signRecovery"     => sign_recovery,
        "trueSign"         => true_sign,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 6 — schottkyThermionicEmission (line 2515-2555)
# ---------------------------------------------------------------------
#
# Math→Physics chain: J = A*·T²·exp(−φ/(k·T)). Sweep T near room-temp +
# φ near 0.4 eV; track max J. F3 spec bound: J ≤ 10⁶ A/cm².
# Exponent gate: if exponent < -60, return 0 instead of calling exp
# (avoids underflow noise per JVM line 2531).

"""
    physics_interdisc_schottky_thermionic_emission(param1, param2, sp) -> Dict
"""
function physics_interdisc_schottky_thermionic_emission(param1::Integer, param2::Real,
                                                         sp::Dict{String,Any})::Dict{String,Any}
    k = 8.617333262e-5   # eV/K
    Astar = 120.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_J = 0.0
    best_T = 0.0
    best_phi = 0.0
    above_limit = 0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        T = 200.0 + (Float64(param1) + i * step_size * 5) * 5.0
        phi = 0.4 + Float64(param2) / 10.0 + i * step_size * 0.02
        exponent = -phi / (k * T)
        exp_term = exponent > -60 ? exp(exponent) : 0.0
        J = Astar * T * T * exp_term
        samples += 1
        if J > best_J
            best_J = J
            best_T = T
            best_phi = phi
        end
        if J > 1e6
            above_limit += 1
        end
    end
    physical = above_limit == 0
    status = !physical ? "REFUTED" :
             best_J > 1e-3 ? "SUPPORTED" : "PARTIAL"
    return Dict{String,Any}(
        "primaryValue"     => best_J,
        "secondaryValue"   => best_T,
        "tertiaryValue"    => best_phi,
        "reviewValue"      => best_J,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_J) && isfinite(best_T) && isfinite(best_phi),
        "bestJ"            => best_J,
        "bestT"            => best_T,
        "bestPhi"          => best_phi,
        "aboveLimit"       => above_limit,
        "samples"          => samples,
        "physical"         => physical,
    )
end

# ---------------------------------------------------------------------
# INTERDISCIPLINARY topic 7 (default) — acousticDopplerShift (line 2562-2603)
# ---------------------------------------------------------------------
#
# Physics↔Physics chain: f' = f·(c+v_o)/(c−v_s). Sweep v_s, v_o
# (each clamped well below c); track max |f'−f|. F3 spec bound:
# |f'−f| ≤ f.

"""
    physics_interdisc_acoustic_doppler_shift(param1, param2, sp) -> Dict
"""
function physics_interdisc_acoustic_doppler_shift(param1::Integer, param2::Real,
                                                   sp::Dict{String,Any})::Dict{String,Any}
    c = 343.0
    f = 1000.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_shift = 0.0
    best_vs = 0.0
    best_vo = 0.0
    best_fp = f
    violates_bound = 0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        v_s = min(c - 5.0, (Float64(param1) + i * step_size * 3) * 0.5)
        v_o = min(c - 5.0, (i * step_size * 1.5 + Float64(param2) * 5))
        fp = f * (c + v_o) / (c - v_s)
        shift = abs(fp - f)
        samples += 1
        if shift > best_shift
            best_shift = shift
            best_vs = v_s
            best_vo = v_o
            best_fp = fp
        end
        if shift > f
            violates_bound += 1
        end
    end
    physical = violates_bound == 0
    status = !physical ? "REFUTED" :
             best_shift > 5.0 ? "SUPPORTED" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => best_shift,
        "secondaryValue"   => best_fp,
        "tertiaryValue"    => best_vs,
        "reviewValue"      => best_shift,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_shift) && isfinite(best_fp) &&
                              isfinite(best_vs),
        "bestShift"        => best_shift,
        "bestVs"           => best_vs,
        "bestVo"           => best_vo,
        "bestFp"           => best_fp,
        "violatesBound"    => violates_bound,
        "samples"          => samples,
        "physical"         => physical,
    )
end

"""
    physics_compute_interdisciplinary(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

INTERDISCIPLINARY-domain dispatch. Mirrors JVM computeInterdisciplinary
(line 2117). Topic 7+ → default branch (acousticDopplerShift).

Ported (topicIdx) → JVM mapping:
  0 → collatzRlcDepthResonance(param1, param2)
  1 → primeGapDefectThermal(param1, param2)
  2 → resonantThermalFatigue(param1, param2)
  3 → happyPackingAnomaly(param1, param2)
  4 → vanDerWaalsCompositeStress(param1, param2)
  5 → hallEffectCarrierSign(param1, param2)
  6 → schottkyThermionicEmission(param1, param2)
  7+ → acousticDopplerShift(param1, param2)   (default branch)
"""
function physics_compute_interdisciplinary(topic_idx::Integer,
                                            param1::Integer, param2::Real,
                                            sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        return physics_interdisc_collatz_rlc_depth_resonance(param1, param2, sp)
    elseif topic_idx == 1
        return physics_interdisc_prime_gap_defect_thermal(param1, param2, sp)
    elseif topic_idx == 2
        return physics_interdisc_resonant_thermal_fatigue(param1, param2, sp)
    elseif topic_idx == 3
        return physics_interdisc_happy_packing_anomaly(param1, param2, sp)
    elseif topic_idx == 4
        return physics_interdisc_van_der_waals_composite_stress(param1, param2, sp)
    elseif topic_idx == 5
        return physics_interdisc_hall_effect_carrier_sign(param1, param2, sp)
    elseif topic_idx == 6
        return physics_interdisc_schottky_thermionic_emission(param1, param2, sp)
    else
        # Topic 7+ — JVM `default` branch routes to acousticDopplerShift.
        return physics_interdisc_acoustic_doppler_shift(param1, param2, sp)
    end
end
