# =====================================================================
# src/physics.jl — Phase 28 §28.5b — physics_compute domain dispatch
# =====================================================================
#
# Top-level `physics_compute(domain, topicIdx, param1, param2, sp)`
# routes to the per-domain implementation file. The MathPhysicsEngine
# JVM surface is 9 domains × ~10 topics (~3,710 lines); §28.5b-cont3
# split each domain into its own file so this dispatch stays small and
# new domain ports land cleanly:
#
#   physics_mathematics.jl  — MATHEMATICS (topics 0..9+, fully ported)
#   physics_physics.jl      — PHYSICS     (topics 0..9+, fully ported)
#   physics_materials.jl    — MATERIALS   (topics 0..9+, fully ported)
#   physics_interdisc.jl    — INTERDISCIPLINARY    (TODO)
#   physics_biology.jl      — BIOLOGY              (TODO)
#   physics_chemistry.jl    — CHEMISTRY            (TODO)
#   physics_arch_terr.jl    — ARCHITECTURE_TERRESTRIAL (TODO)
#   physics_arch_liquid.jl  — ARCHITECTURE_LIQUID  (TODO)
#   physics_arch_space.jl   — ARCHITECTURE_SPACE   (TODO)
#
# `physics_compute` returns `nothing` for any (domain, topicIdx) pair
# whose domain port has not yet landed; the /physics/topic handler
# translates that into a 404 + not_ported error code, and JVM
# JuliaMathClient.physicsTopic surfaces it as Optional.empty() so
# callers fall back to in-process MathPhysicsEngine.compute.
#
# Wire contract (matches §28.5a /predicate/scan): numeric scalars +
# ClaimStatus name only. The JVM caller composes the findings /
# hypothesis / equation prose locally.

"""
    physics_compute(domain, topic_idx, param1, param2, sp) -> Union{Dict, Nothing}

Top-level dispatch. Returns the numeric scalars dict on ported
(domain, topicIdx) pairs; returns `nothing` to signal not-ported so
the handler can surface a 404-equivalent and the JVM falls back to
legacy.

Ported domains:
  MATHEMATICS — topics 0..9+ (all topics; topic 9+ routes to the
                              JVM `default` branch, squarefreeDensity)
  PHYSICS     — topics 0..9+ (all topics; topic 9+ routes to the
                              JVM `default` branch, stefanBoltzmann)
  MATERIALS   — topics 0..9+ (all topics; topic 9+ routes to the
                              JVM `default` branch, youngLaplaceCapillary)

Pending domains (each will gain a `physics_<domain>.jl` file):
  INTERDISCIPLINARY, BIOLOGY, CHEMISTRY,
  ARCHITECTURE_TERRESTRIAL, ARCHITECTURE_LIQUID, ARCHITECTURE_SPACE.
"""
function physics_compute(domain::String, topic_idx::Integer,
                          param1::Integer, param2::Real,
                          sp::Dict{String,Any})::Union{Dict{String,Any}, Nothing}
    if domain == "MATHEMATICS"
        return physics_compute_mathematics(topic_idx, param1, param2, sp)
    elseif domain == "PHYSICS"
        return physics_compute_physics(topic_idx, param1, param2, sp)
    elseif domain == "MATERIALS"
        return physics_compute_materials(topic_idx, param1, param2, sp)
    end
    # Not ported. JVM caller falls back to in-process MathPhysicsEngine.
    return nothing
end
