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

"""
    physics_compute_physics(topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

PHYSICS-domain dispatch. Currently ports topic 0 (projectileWithDrag)
as the §28.5c vertical slice. Topics 1..9 fall through to `nothing`
so JVM JuliaMathClient.physicsTopic surfaces Optional.empty() and
the caller falls back to in-process MathPhysicsEngine.compute.

Ported (topicIdx) → JVM mapping:
  0 → physics_projectile_with_drag(10.0 + param2·5, param1/1000.0)

Remaining JVM dispatch (line 1268-1280) — all return `nothing` until
ported in §28.5c continuations:
  1 — pendulumBreakdown
  2 — vanDerWaalsDeviation
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
    end
    # Topic 1..9+ not yet ported.
    return nothing
end
