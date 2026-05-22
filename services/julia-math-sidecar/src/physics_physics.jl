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
    # JVM: `for (int deg = 5; deg <= 80; deg += 5)` — match exactly.
    for deg in 5:5:80
        k  = sin(deg * DEGREES_TO_RADIANS / 2)
        k2 = k * k
        T  = T0 * (1 + k2 / 4 + 9 * k2 * k2 / 64 + 25 * k2 * k2 * k2 / 256)
        err = (T - T0) / T0 * 100
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

"""
    physics_compute_physics(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

PHYSICS-domain dispatch. Currently ports topic 0 (projectileWithDrag)
as the §28.5c vertical slice. Topics 1..9 fall through to `nothing`
so JVM JuliaMathClient.physicsTopic surfaces Optional.empty() and
the caller falls back to in-process MathPhysicsEngine.compute.

Ported (topicIdx) → JVM mapping:
  0 → physics_projectile_with_drag(10.0 + param2·5, param1/1000.0)
  1 → physics_pendulum_breakdown(0.1 + param1/200.0)
  2 → physics_van_der_waals_deviation(200.0 + param1·3, 0.01)

Remaining JVM dispatch (line 1268-1280) — return `nothing` until
ported in §28.5c continuations:
  3 — diffractionSurvey
  4 — rlcDampingTransition
  5 — wienPeak
  6 — schrodingerInfiniteWell
  7 — keplerThirdLaw
  8 — snellCriticalAngle
  9+ — stefanBoltzmann (JVM `default` branch)
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
    end
    # Topic 3..9+ not yet ported.
    return nothing
end
