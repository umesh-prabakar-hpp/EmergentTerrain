# =====================================================================
# src/physics_physics.jl — Phase 28 §28.5c — PHYSICS domain
# =====================================================================
#
# Ports of MathPhysicsEngine.computePhysics topic implementations.
# Loaded by JuliaMathSidecar.jl alongside the other per-domain files;
# physics_compute (in physics.jl) dispatches here when domain ==
# "PHYSICS". Wire contract matches MATHEMATICS: numeric scalars +
# ClaimStatus name; JVM caller composes the prose.
#
# Bit-identity discipline (same as the riemann/euler port in §28.5b-cont2):
# use the same constant literals the JVM uses, prefer integer-exponent
# pow over fractional, and keep loop order + arithmetic associativity
# byte-for-byte with the Java source. Java Math.sin/cos/sqrt/asin/pow
# on x86-64 OpenJDK match Julia's sin/cos/sqrt/asin/^ on openlibm —
# the riemann-euler-product parity test (5000-term zeta sum, primes ≤
# 200 Euler product) is the existence proof.

# ---------------------------------------------------------------------
# PHYSICS topic 0 — projectileWithDrag (line 1285-1322)
# ---------------------------------------------------------------------
#
# Optimal launch-angle search under quadratic drag. For each angle
# 5°..85° in 1° steps, Euler-integrate the trajectory at dt=0.03s up
# to 5000 steps or ground contact (y < -0.01 m); record the longest
# range. Primary value = 45 − best_angle (the angle shift away from
# the vacuum optimum); secondary = best range; tertiary = fractional
# range loss vs the vacuum closed-form.
#
# Inner Euler step (JVM line 1305-1309):
#   spd = √(vx² + vy²)
#   vx -= k·spd·vx·dt
#   vy -= (g + k·spd·vy)·dt
#   x  += vx·dt
#   y  += vy·dt
# The vy update sees the JUST-updated vx via spd computed before the
# update, but the position update uses the JUST-updated v — preserve
# that ordering exactly.
#
# Java Math.toRadians(a) = a · DEGREES_TO_RADIANS where the constant
# is `0.017453292519943295` (the compile-time-folded value of
# Math.PI/180). Julia's `deg2rad(a)` for integer a evaluates to the
# same Float64 constant, but we hard-code the literal here to keep
# the bit-identity argument explicit and to insure against future
# Julia stdlib changes to deg2rad.

const DEGREES_TO_RADIANS = 0.017453292519943295

"""
    physics_projectile_with_drag_at(v0, k) -> Dict

Inner per-(v0, k) search: scan launch angle 5°..85°, integrate
ballistic trajectory under quadratic drag (Euler, dt=0.03s, up to
5000 steps). Returns the angle shift away from the vacuum 45°
optimum, the best range, and the fractional loss.
"""
function physics_projectile_with_drag_at(v0::Real, k::Real)::Dict{String,Any}
    g = 9.81
    dt = 0.03
    best_angle = 45
    best_range = 0.0
    for a in 5:85
        # Angle → radians using the same literal Java compiles in.
        rad = a * DEGREES_TO_RADIANS
        vx = v0 * cos(rad)
        vy = v0 * sin(rad)
        x = 0.0
        y = 0.0
        i = 0
        # JVM line 1304: `for (int i = 0; i < 5000 && y >= -0.01; i++)`.
        # The y-bound check happens BEFORE the body — match exactly.
        while i < 5000 && y >= -0.01
            spd = sqrt(vx * vx + vy * vy)
            # Order matters: vx is updated first; vy reads the OLD vx
            # via spd (which was computed before this line).
            vx -= k * spd * vx * dt
            vy -= (g + k * spd * vy) * dt
            x += vx * dt
            y += vy * dt
            i += 1
        end
        if x > best_range
            best_range = x
            best_angle = a
        end
    end
    vacuum_range = v0 * v0 / g
    shift = 45 - best_angle
    loss  = (vacuum_range - best_range) / vacuum_range
    # JVM line 1316: 3-arg ResearchResult constructor (no status arg)
    # → claimStatus defaults to OBSERVATIONAL when allFinite, REFUTED
    # otherwise (the in-process MathPhysicsEngine.ResearchResult ctor
    # convention; see §28.5b-cont's collatz port for the same pattern).
    return Dict{String,Any}(
        "primaryValue"     => Float64(shift),
        "secondaryValue"   => best_range,
        "tertiaryValue"    => loss,
        "reviewValue"      => Float64(shift),
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(Float64(shift)) && isfinite(best_range) &&
                              isfinite(loss),
        "bestAngle"        => best_angle,
        "bestRange"        => best_range,
        "vacuumRange"      => vacuum_range,
        "shift"            => shift,
        "loss"             => loss,
        "v0"               => Float64(v0),
        "k"                => Float64(k),
    )
end

"""
    physics_projectile_with_drag(seed_v0, k, sp) -> Dict

Outer search wrapper: scan candidateBreadth v0 values spaced by
stepSize·5 m/s starting at max(2, seed_v0). Returns the (v0, k)
sample with the largest angle shift (primaryValue).
"""
function physics_projectile_with_drag(seed_v0::Real, k::Real,
                                       sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        v0 = max(2.0, Float64(seed_v0) + i * step_size * 5.0)
        r = physics_projectile_with_drag_at(v0, k)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_projectile_with_drag_at(seed_v0, k) : best
end

# ---------------------------------------------------------------------
# PHYSICS topic 1 — pendulumBreakdown (line 1327-1369)
# ---------------------------------------------------------------------
#
# Small-angle period T₀ = 2π√(L/g). Sweep amplitude 5°..80° in 5°
# steps; the period at finite amplitude θ uses the elliptic-integral
# series T = T₀·(1 + k²/4 + 9k⁴/64 + 25k⁶/256) where k = sin(θ/2).
# Record the first amplitude where the relative error vs T₀ exceeds
# 1%; primary value is T₀·(80 − breakdownAngle).

"""
    physics_pendulum_breakdown_at(L) -> Dict

Inner: for pendulum length L (m), find the first amplitude θ in
5°..80° (step 5°) where the finite-amplitude period diverges from
T₀ = 2π√(L/g) by more than 1%. Status bands match JVM line 1355-1356:
breakdownAngle < 20 → SUPPORTED; ≈ 20 → PARTIAL; else REFUTED.
"""
function physics_pendulum_breakdown_at(L::Real)::Dict{String,Any}
    g  = 9.81
    T0 = 2 * pi * sqrt(Float64(L) / g)
    breakdown_angle = 80
    sample_sweep = Dict{String,Any}[]   # §28.6b — all 16 amplitude steps for JVM sweep string
    # JVM: `for (int deg = 5; deg <= 80; deg += 5)` — match exactly.
    for deg in 5:5:80
        k  = sin(deg * DEGREES_TO_RADIANS / 2)
        k2 = k * k
        T  = T0 * (1 + k2 / 4 + 9 * k2 * k2 / 64 + 25 * k2 * k2 * k2 / 256)
        err = (T - T0) / T0 * 100
        push!(sample_sweep, Dict{String,Any}("deg" => deg, "err" => err))
        # JVM line 1353: only set breakdownAngle the FIRST time err > 1%
        # — `nearlyEqual(breakdownAngle, 80, 0.5)` guards against
        # overwrite. Mirror the 0.5-tolerance equality check.
        if err > 1.0 && abs(breakdown_angle - 80) <= 0.5
            breakdown_angle = deg
        end
    end
    # Status banding from JVM line 1355-1356.
    status = breakdown_angle < 20 ? "SUPPORTED" :
             abs(breakdown_angle - 20) <= 0.5 ? "PARTIAL" :
             "REFUTED"
    primary = T0 * (80 - breakdown_angle)
    return Dict{String,Any}(
        "primaryValue"     => primary,
        "secondaryValue"   => T0,
        "tertiaryValue"    => Float64(L),
        "reviewValue"      => primary,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(primary) && isfinite(T0) &&
                              isfinite(Float64(L)),
        "L"                => Float64(L),
        "T0"               => T0,
        "breakdownAngle"   => breakdown_angle,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

"""
    physics_pendulum_breakdown(seed_L, sp) -> Dict

Outer search: scan candidateBreadth pendulum lengths spaced by
stepSize·0.05 m starting at max(0.01, seed_L). Return the (L, T₀,
breakdown) sample with the largest primary value.
"""
function physics_pendulum_breakdown(seed_L::Real, sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        L = max(0.01, Float64(seed_L) + i * step_size * 0.05)
        r = physics_pendulum_breakdown_at(L)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_pendulum_breakdown_at(seed_L) : best
end

# ---------------------------------------------------------------------
# PHYSICS topic 2 — vanDerWaalsDeviation (line 1373-1417)
# ---------------------------------------------------------------------
#
# Van der Waals CO₂: for each P in 1..100 atm at temperature T,
# Newton-iterate the cubic
#   (P + a/V²)·(V − b) = R·T
# from V = V_ideal = R·T/P to convergence (30 iters max, derivative-
# floor guard at 1e-15, V > b clamp). Track |V − V_ideal|/V_ideal
# across P; record max deviation and the first P where the deviation
# exceeds `tolerance`.

"""
    physics_van_der_waals_deviation_at(T, tolerance) -> Dict

Inner: for CO₂ at temperature T (K), sweep P = 1..100 atm, Newton-
iterate the VdW cubic for V, and track the max relative deviation
from V_ideal. Returns the deviation in percent (primaryValue),
the first crossover P (or 101.0 if none), and T.
"""
function physics_van_der_waals_deviation_at(T::Real, tolerance::Real)::Dict{String,Any}
    a = 3.640
    b = 0.04267
    R = 0.08206
    crossover_P = -1.0
    max_dev = 0.0
    for p in 1:100
        P = Float64(p)
        V_ideal = R * Float64(T) / P
        V = V_ideal
        # JVM line 1390: 30-iter cap, 1e-15 derivative-zero guard, V > b clamp.
        for _ in 1:30
            f  = (P + a / (V * V)) * (V - b) - R * Float64(T)
            df = (P + a / (V * V)) - 2 * a * (V - b) / (V * V * V)
            if abs(df) < 1e-15
                break
            end
            step = f / df
            V -= step
            if V <= b
                V = b * 1.01
                break
            end
        end
        dev = abs(V - V_ideal) / V_ideal
        if dev > max_dev
            max_dev = dev
        end
        if dev > Float64(tolerance) && crossover_P < 0
            crossover_P = Float64(p)
        end
    end
    crossover_observed = crossover_P > 0
    # JVM line 1413-1416: 4-arg ResearchResult (status defaults to
    # OBSERVATIONAL when finite). Tertiary is T (passed as-is, no
    # conversion).
    primary = max_dev * 100
    secondary = crossover_observed ? crossover_P : 101.0
    tertiary = Float64(T)
    return Dict{String,Any}(
        "primaryValue"     => primary,
        "secondaryValue"   => secondary,
        "tertiaryValue"    => tertiary,
        "reviewValue"      => primary,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(primary) && isfinite(secondary) &&
                              isfinite(tertiary),
        "T"                => tertiary,
        "maxDev"           => max_dev,
        "crossoverP"       => crossover_P,
        "crossoverObserved" => crossover_observed,
    )
end

"""
    physics_van_der_waals_deviation(seed_T, tolerance, sp) -> Dict

Outer: scan candidateBreadth temperatures spaced by stepSize·20 K
starting at max(150, seed_T). Return the sample with the largest
percentage deviation.
"""
function physics_van_der_waals_deviation(seed_T::Real, tolerance::Real,
                                          sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        T = max(150.0, Float64(seed_T) + i * step_size * 20.0)
        r = physics_van_der_waals_deviation_at(T, tolerance)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_van_der_waals_deviation_at(seed_T, tolerance) : best
end

# ---------------------------------------------------------------------
# PHYSICS topic 3 — diffractionSurvey (line 1422-1453)
# ---------------------------------------------------------------------
#
# Grating-diffraction order count vs wavelength. For grating spacing
# d (µm) and reference wavelength λ_ref (nm), maxOrder is the largest
# integer m such that m·λ ≤ d (in matched units; the JVM uses
# `(int)(dMicrons*1000/lambdaNm)` — truncation toward 0). Sweep a
# fixed 7-wavelength array {400..700 nm}; record minOrders and
# maxOrders; primary value is the ratio maxOrders/max(1, minOrders).

# Fixed wavelength sweep — keep as a Tuple so the JIT can specialise.
const DIFFRACTION_WAVELENGTHS_NM = (400.0, 450.0, 500.0, 550.0, 600.0, 650.0, 700.0)

"""
    physics_diffraction_survey_at(d_microns, lambda_nm) -> Dict

Inner per-grating: compute maxOrder + first-order angle θ₁ at the
reference wavelength, then sweep 7 wavelengths to bracket the
order-count range. Returns the order ratio (primary), maxOrder
(secondary), θ₁ in degrees (tertiary).
"""
function physics_diffraction_survey_at(d_microns::Real, lambda_nm::Real)::Dict{String,Any}
    # JVM line 1434: `(int)(dMicrons * 1000 / lambdaNm)` — Java casts a
    # positive double to int by truncation toward 0; Julia's `trunc(Int, ...)`
    # matches that semantic exactly.
    max_order = trunc(Int, Float64(d_microns) * 1000.0 / Float64(lambda_nm))
    theta1 = max_order >= 1 ?
        rad2deg(asin(min(1.0, Float64(lambda_nm) / (Float64(d_microns) * 1000.0)))) :
        0.0
    min_orders = typemax(Int)
    max_orders = 0
    sample_sweep = Dict{String,Any}[]   # §28.6b — 7 wavelengths for JVM sweep string
    for lam in DIFFRACTION_WAVELENGTHS_NM
        orders = trunc(Int, Float64(d_microns) * 1000.0 / lam)
        if orders < min_orders; min_orders = orders end
        if orders > max_orders; max_orders = orders end
        push!(sample_sweep, Dict{String,Any}("lam" => lam, "orders" => orders))
    end
    order_ratio = Float64(max_orders) / Float64(max(1, min_orders))
    # JVM line 1446-1452: 3-arg ResearchResult (findings, primary,
    # secondary, tertiary) → status defaults to OBSERVATIONAL.
    return Dict{String,Any}(
        "primaryValue"     => order_ratio,
        "secondaryValue"   => Float64(max_order),
        "tertiaryValue"    => theta1,
        "reviewValue"      => order_ratio,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(order_ratio) &&
                              isfinite(Float64(max_order)) && isfinite(theta1),
        "dMicrons"         => Float64(d_microns),
        "lambdaNm"         => Float64(lambda_nm),
        "maxOrder"         => max_order,
        "theta1"           => theta1,
        "minOrders"        => min_orders == typemax(Int) ? 0 : min_orders,
        "maxOrders"        => max_orders,
        "orderRatio"       => order_ratio,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

"""
    physics_diffraction_survey(seed_d, lambda_nm, sp) -> Dict

Outer: scan candidateBreadth grating spacings spaced by
stepSize·0.5 µm starting at max(0.4, seed_d).
"""
function physics_diffraction_survey(seed_d::Real, lambda_nm::Real,
                                     sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        d = max(0.4, Float64(seed_d) + i * step_size * 0.5)
        r = physics_diffraction_survey_at(d, lambda_nm)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_diffraction_survey_at(seed_d, lambda_nm) : best
end

# ---------------------------------------------------------------------
# PHYSICS topic 4 — rlcDampingTransition (line 1457-1488)
# ---------------------------------------------------------------------
#
# RLC resonant circuit. Given L (H), C (F): critR = 2√(L/C), ω₀ = 1/√(L·C),
# f₀ = ω₀/(2π). Sweep R = critR·mult for 6 fixed multipliers; Q = ω₀·L/R.
# Outer search uses a DIVISIVE step (L decreases as i increases) per the
# JVM line 1461: `L = max(1e-5, seedL / (1.0 + i*stepSize*0.2))`.

const RLC_R_MULTIPLIERS = (0.1, 0.25, 0.5, 1.0, 2.0, 4.0)

"""
    physics_rlc_damping_transition_at(L, C) -> Dict

Inner: for inductance L and capacitance C, compute resonance + a
Q-factor sweep across 6 R/R_crit multipliers. Returns f₀ (primary),
critR (secondary), and the Q range ratio maxQ/max(minQ, 1e-10)
(tertiary).
"""
function physics_rlc_damping_transition_at(L::Real, C::Real)::Dict{String,Any}
    L_f = Float64(L)
    C_f = Float64(C)
    crit_R  = 2 * sqrt(L_f / C_f)
    omega0  = 1 / sqrt(L_f * C_f)
    f0      = omega0 / (2 * pi)
    min_Q = floatmax(Float64)
    max_Q = 0.0
    sample_sweep = Dict{String,Any}[]   # §28.6b — 6 R/R_crit multipliers for JVM sweep string
    for mult in RLC_R_MULTIPLIERS
        R = crit_R * mult
        Q = omega0 * L_f / R
        if Q < min_Q; min_Q = Q end
        if Q > max_Q; max_Q = Q end
        push!(sample_sweep, Dict{String,Any}("mult" => mult, "Q" => Q))
    end
    q_range = max_Q / max(min_Q, 1e-10)
    return Dict{String,Any}(
        "primaryValue"     => f0,
        "secondaryValue"   => crit_R,
        "tertiaryValue"    => q_range,
        "reviewValue"      => f0,
        "claimStatus"      => "OBSERVATIONAL",
        "theoryConsistent" => isfinite(f0) && isfinite(crit_R) && isfinite(q_range),
        "L"                => L_f,
        "C"                => C_f,
        "f0"               => f0,
        "critR"            => crit_R,
        "minQ"             => min_Q,
        "maxQ"             => max_Q,
        "qRange"           => q_range,
        "sampleSweep"      => sample_sweep,   # §28.6b — JVM rebuilds the sweep prose
    )
end

"""
    physics_rlc_damping_transition(seed_L, C, sp) -> Dict

Outer: scan candidateBreadth inductances L = max(1e-5, seedL / (1 +
i·stepSize·0.2)). Note this is a DIVISIVE step — lower L gives
higher f₀ — per JVM line 1461.
"""
function physics_rlc_damping_transition(seed_L::Real, C::Real,
                                         sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best::Union{Nothing, Dict{String,Any}} = nothing
    best_extreme = -1.0
    for i in 0:(candidate_breadth - 1)
        L = max(1e-5, Float64(seed_L) / (1.0 + i * step_size * 0.2))
        r = physics_rlc_damping_transition_at(L, C)
        if r["primaryValue"] > best_extreme
            best_extreme = r["primaryValue"]
            best = r
        end
    end
    return best === nothing ?
        physics_rlc_damping_transition_at(seed_L, C) : best
end

# ---------------------------------------------------------------------
# PHYSICS topic 5 — wienPeak (line 1493-1529)
# ---------------------------------------------------------------------
#
# Wien displacement law λ_max·T = b where b = 2.8977719e-3 m·K.
# Inline-sweep pattern (no inner-at split): for each i in [0, breadth),
# T = max(100, seedT + i·stepSize·200); λ_max = b/T (closed-form), then
# a synthetic "observed" λ_max with 0.5% sinusoidal noise; track the
# worst |observed·T − b|/b deviation. Status bands: ≤5% → SUPPORTED,
# ≤10% → PARTIAL, else REFUTED.

"""
    physics_wien_peak(seed_T, sp) -> Dict
"""
function physics_wien_peak(seed_T::Real, sp::Dict{String,Any})::Dict{String,Any}
    wien_b = 2.8977719e-3
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_T = Float64(seed_T)
    best_lambda = 0.0
    samples = 0
    sum_dev = 0.0
    for i in 0:(candidate_breadth - 1)
        T = max(100.0, Float64(seed_T) + i * step_size * 200.0)
        lambda_max = wien_b / T
        observed = lambda_max * (1.0 + sin(i * 0.37) * 0.005)
        dev = abs(observed * T - wien_b) / wien_b
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_T = T
            best_lambda = observed
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    status = best_dev <= 0.05 ? "SUPPORTED" :
             best_dev <= 0.10 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => mean_dev,
        "tertiaryValue"    => best_lambda,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(mean_dev) &&
                              isfinite(best_lambda),
        "bestT"            => best_T,
        "bestLambda"       => best_lambda,
        "meanDev"          => mean_dev,
        "samples"          => samples,
        "wienB"            => wien_b,
        "seedT"            => Float64(seed_T),
    )
end

# ---------------------------------------------------------------------
# PHYSICS topic 6 — schrodingerInfiniteWell (line 1532-1567)
# ---------------------------------------------------------------------
#
# Particle-in-a-1D-infinite-well ground-state E₁ = h²/(8·m_e·L²).
# Sweep Lnm DOWNWARD (Lnm = max(0.1, seedLnm − i·stepSize·0.05)) and
# track max E₁/(kB·T). Status: ratio > 5 → SUPPORTED, > 1 → PARTIAL,
# else OBSERVATIONAL (the F3.2 "confinement vs thermal" diagnostic).

"""
    physics_schrodinger_infinite_well(seed_L_nm, param2, sp) -> Dict
"""
function physics_schrodinger_infinite_well(seed_L_nm::Real, param2::Real,
                                            sp::Dict{String,Any})::Dict{String,Any}
    h  = 6.62607015e-34
    me = 9.1093837015e-31
    kB = 1.380649e-23
    T  = 300.0 + Float64(param2) * 30.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_ratio = 0.0
    best_L = Float64(seed_L_nm)
    best_E1 = 0.0
    samples = 0
    for i in 0:(candidate_breadth - 1)
        Lnm = max(0.1, Float64(seed_L_nm) - i * step_size * 0.05)
        Lm  = Lnm * 1e-9
        E1  = (h * h) / (8.0 * me * Lm * Lm)
        ratio = E1 / (kB * T)
        samples += 1
        if ratio > best_ratio
            best_ratio = ratio
            best_L = Lnm
            best_E1 = E1
        end
    end
    confinement_dominant = best_ratio > 1.0
    status = best_ratio > 5.0 ? "SUPPORTED" :
             confinement_dominant ? "PARTIAL" : "OBSERVATIONAL"
    return Dict{String,Any}(
        "primaryValue"     => best_ratio,
        "secondaryValue"   => best_E1,
        "tertiaryValue"    => best_L,
        "reviewValue"      => best_ratio,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_ratio) && isfinite(best_E1) &&
                              isfinite(best_L),
        "bestRatio"        => best_ratio,
        "bestE1"           => best_E1,
        "bestL"            => best_L,
        "T"                => T,
        "samples"          => samples,
        "seedLnm"          => Float64(seed_L_nm),
    )
end

# ---------------------------------------------------------------------
# PHYSICS topic 7 — keplerThirdLaw (line 1570-1610)
# ---------------------------------------------------------------------
#
# Kepler's third law T² = (4π²/GM)·a³. For solar M, T (years) and
# a (AU) satisfy T²/a³ = 1. Sweep aAU upward; compute Tyear, perturb
# with 2% sinusoidal noise; track worst |T²/a³ − 1|.

const KEPLER_GMSUN = 1.32712440018e20    # m³/s²
const KEPLER_AU = 1.495978707e11          # m
const KEPLER_SEC_PER_YEAR = 3.15576e7

"""
    physics_kepler_third_law(seed_aAU, sp) -> Dict
"""
function physics_kepler_third_law(seed_aAU::Real, sp::Dict{String,Any})::Dict{String,Any}
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_a = Float64(seed_aAU)
    best_T = 0.0
    samples = 0
    sum_dev = 0.0
    for i in 0:(candidate_breadth - 1)
        aAU = max(0.05, Float64(seed_aAU) + i * step_size * 0.5)
        aM = aAU * KEPLER_AU
        Tsec = 2 * pi * sqrt(aM * aM * aM / KEPLER_GMSUN)
        Tyear = Tsec / KEPLER_SEC_PER_YEAR
        observedT = Tyear * (1.0 + sin(i * 0.51) * 0.02)
        ratio = (observedT * observedT) / (aAU * aAU * aAU)
        dev = abs(ratio - 1.0)
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_a = aAU
            best_T = observedT
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    status = best_dev <= 0.10 ? "SUPPORTED" :
             best_dev <= 0.20 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => mean_dev,
        "tertiaryValue"    => best_T,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(mean_dev) &&
                              isfinite(best_T),
        "bestA"            => best_a,
        "bestT"            => best_T,
        "meanDev"          => mean_dev,
        "samples"          => samples,
        "seedAuAu"         => Float64(seed_aAU),
    )
end

# ---------------------------------------------------------------------
# PHYSICS topic 8 — snellCriticalAngle (line 1613-1647)
# ---------------------------------------------------------------------
#
# θ_c = arcsin(n₂/n₁) for n₁ > n₂. Fixed n₁ = 1.5 (glass); sweep n₂
# with clamp [1.0, n₁ − 0.01]; perturb θ_c by up to 1.5° sinusoidal
# noise; track worst |θ_predicted − θ_observed|.

"""
    physics_snell_critical_angle(seed_n2, sp) -> Dict
"""
function physics_snell_critical_angle(seed_n2::Real, sp::Dict{String,Any})::Dict{String,Any}
    n1 = 1.5
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_err = 0.0
    best_n2 = Float64(seed_n2)
    best_theta = 0.0
    samples = 0
    sum_err = 0.0
    for i in 0:(candidate_breadth - 1)
        # JVM line 1622: clamp via nested Math.max + Math.min — match exactly.
        n2 = max(1.0, min(n1 - 0.01, Float64(seed_n2) + i * step_size * 0.05))
        thetaC = rad2deg(asin(n2 / n1))
        observed = thetaC + sin(i * 0.43) * 1.5
        err = abs(thetaC - observed)
        sum_err += err
        samples += 1
        if err > best_err
            best_err = err
            best_n2 = n2
            best_theta = thetaC
        end
    end
    mean_err = samples > 0 ? sum_err / samples : 0.0
    status = best_err <= 2.0 ? "SUPPORTED" :
             best_err <= 4.0 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_err,
        "secondaryValue"   => mean_err,
        "tertiaryValue"    => best_theta,
        "reviewValue"      => best_err,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_err) && isfinite(mean_err) &&
                              isfinite(best_theta),
        "bestN2"           => best_n2,
        "bestTheta"        => best_theta,
        "meanErr"          => mean_err,
        "samples"          => samples,
        "n1"               => n1,
        "seedN2"           => Float64(seed_n2),
    )
end

# ---------------------------------------------------------------------
# PHYSICS topic 9 (default) — stefanBoltzmann (line 1650-1686)
# ---------------------------------------------------------------------
#
# P = σ·A·T⁴ for a unit-area emitter. Sweep T upward; synthetic
# observation with 3% sinusoidal noise; recover σ_obs = P_obs/(A·T⁴);
# track worst |σ_obs − σ|/σ.

"""
    physics_stefan_boltzmann(seed_T, sp) -> Dict
"""
function physics_stefan_boltzmann(seed_T::Real, sp::Dict{String,Any})::Dict{String,Any}
    sigma_true = 5.670374419e-8
    A = 1.0
    candidate_breadth = Int(sp["candidateBreadth"])
    step_size         = Float64(sp["stepSize"])
    best_dev = 0.0
    best_T = Float64(seed_T)
    best_sigma = 0.0
    samples = 0
    sum_dev = 0.0
    for i in 0:(candidate_breadth - 1)
        T = max(100.0, Float64(seed_T) + i * step_size * 100.0)
        # JVM uses Math.pow(T, 4); for integer exponent both Java and
        # Julia reduce to T·T·T·T (bit-identical). Spell it out so the
        # reader doesn't need to trust the JIT.
        T4 = T * T * T * T
        P = sigma_true * A * T4
        observed_P = P * (1.0 + sin(i * 0.29) * 0.03)
        sigma_obs = observed_P / (A * T4)
        dev = abs(sigma_obs - sigma_true) / sigma_true
        sum_dev += dev
        samples += 1
        if dev > best_dev
            best_dev = dev
            best_T = T
            best_sigma = sigma_obs
        end
    end
    mean_dev = samples > 0 ? sum_dev / samples : 0.0
    status = best_dev <= 0.10 ? "SUPPORTED" :
             best_dev <= 0.20 ? "PARTIAL" : "REFUTED"
    return Dict{String,Any}(
        "primaryValue"     => best_dev,
        "secondaryValue"   => mean_dev,
        "tertiaryValue"    => best_sigma,
        "reviewValue"      => best_dev,
        "claimStatus"      => status,
        "theoryConsistent" => isfinite(best_dev) && isfinite(mean_dev) &&
                              isfinite(best_sigma),
        "bestT"            => best_T,
        "bestSigma"        => best_sigma,
        "meanDev"          => mean_dev,
        "samples"          => samples,
        "sigmaTrue"        => sigma_true,
        "seedT"            => Float64(seed_T),
    )
end

"""
    physics_compute_physics(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

PHYSICS-domain dispatch. Currently ports topic 0 (projectileWithDrag)
as the §28.5c vertical slice. Topics 1..9 fall through to `nothing`
so JVM JuliaMathClient.physicsTopic surfaces Optional.empty() and
the caller falls back to in-process MathPhysicsEngine.compute.

PHYSICS domain is fully ported (10/10):
  0 → physics_projectile_with_drag(10.0 + param2·5, param1/1000.0)
  1 → physics_pendulum_breakdown(0.1 + param1/200.0)
  2 → physics_van_der_waals_deviation(200.0 + param1·3, 0.01)
  3 → physics_diffraction_survey(1.0 + param1/50.0, 400.0 + param2·40)
  4 → physics_rlc_damping_transition(0.001 + param1/10000.0,
                                      1e-6 + param2·1e-7)
  5 → physics_wien_peak(2000.0 + param1·30)
  6 → physics_schrodinger_infinite_well(1.0 + param1/10, param2)
  7 → physics_kepler_third_law(0.5 + param1/20)
  8 → physics_snell_critical_angle(1.0 + param2/9)
  9+ → physics_stefan_boltzmann(300.0 + param1·50)   (JVM `default` branch)
"""
function physics_compute_physics(topic_idx::Integer,
                                  param1::Integer, param2::Real,
                                  sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if topic_idx == 0
        # JVM line 1269: projectileWithDrag(10.0 + param2*5, param1/1000.0, sp).
        # param1 is int → Java integer division: param1/1000 is 0 for
        # param1 < 1000, but here it's `param1 / 1000.0` (Java promotes
        # to double). Mirror with Float64 division to be safe.
        seed_v0 = 10.0 + Float64(param2) * 5.0
        k       = Float64(param1) / 1000.0
        return physics_projectile_with_drag(seed_v0, k, sp)
    elseif topic_idx == 1
        # JVM line 1270: pendulumBreakdown(0.1 + param1 / 200.0, sp).
        seed_L = 0.1 + Float64(param1) / 200.0
        return physics_pendulum_breakdown(seed_L, sp)
    elseif topic_idx == 2
        # JVM line 1271: vanDerWaalsDeviation(200.0 + param1 * 3.0, 0.01, sp).
        seed_T = 200.0 + Float64(param1) * 3.0
        return physics_van_der_waals_deviation(seed_T, 0.01, sp)
    elseif topic_idx == 3
        # JVM line 1272: diffractionSurvey(1.0 + param1/50.0, 400.0 + param2*40, sp).
        seed_d   = 1.0 + Float64(param1) / 50.0
        lambdaNm = 400.0 + Float64(param2) * 40.0
        return physics_diffraction_survey(seed_d, lambdaNm, sp)
    elseif topic_idx == 4
        # JVM line 1273: rlcDampingTransition(0.001 + param1/10000.0,
        # 1e-6 + param2*1e-7, sp).
        seed_L = 0.001 + Float64(param1) / 10000.0
        C      = 1e-6 + Float64(param2) * 1e-7
        return physics_rlc_damping_transition(seed_L, C, sp)
    elseif topic_idx == 5
        # JVM line 1275: wienPeak(2000.0 + param1 * 30.0, sp).
        seed_T = 2000.0 + Float64(param1) * 30.0
        return physics_wien_peak(seed_T, sp)
    elseif topic_idx == 6
        # JVM line 1276: schrodingerInfiniteWell(1.0 + param1 / 10.0, param2, sp).
        seed_L = 1.0 + Float64(param1) / 10.0
        return physics_schrodinger_infinite_well(seed_L, param2, sp)
    elseif topic_idx == 7
        # JVM line 1277: keplerThirdLaw(0.5 + param1 / 20.0, sp).
        seed_aAU = 0.5 + Float64(param1) / 20.0
        return physics_kepler_third_law(seed_aAU, sp)
    elseif topic_idx == 8
        # JVM line 1278: snellCriticalAngle(1.0 + param2 / 9.0, sp).
        seed_n2 = 1.0 + Float64(param2) / 9.0
        return physics_snell_critical_angle(seed_n2, sp)
    else
        # JVM line 1279: default → stefanBoltzmann(300.0 + param1 * 50.0, sp).
        # Routes topic 9+ here per the JVM `default` branch in computePhysics.
        seed_T = 300.0 + Float64(param1) * 50.0
        return physics_stefan_boltzmann(seed_T, sp)
    end
end
