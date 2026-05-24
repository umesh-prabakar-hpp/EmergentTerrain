# =====================================================================
# JuliaMathSidecar — main module
# =====================================================================
#
# Generic HTTP sidecar for math evaluation. Designed to be called from
# any JVM/Java/Python/etc. process that wants Julia's symbolic +
# numerical math libraries without embedding libjulia directly.
#
# Why this exists
# ---------------
# EmergentTerrain's in-process math engine (Symja, Java) handles the
# common-case symbolic ops but hits walls for:
#   - hard integrals (Symja's Risch implementation is incomplete);
#   - ODE systems beyond linear (DifferentialEquations.jl is the
#     gold-standard open-source ODE library);
#   - native Julia-style code emission from LLMs that don't know
#     Mathematica syntax conventions.
#
# This sidecar exposes a small JSON HTTP surface — `/eval`, `/solve`,
# `/nsolve`, `/integrate`, `/sweep`, `/healthz`, `/metrics` — and runs
# every user-supplied expression through a safety filter + timeout
# wrapper before evaluation.
#
# Scope this is NOT
# -----------------
#   - A full Mathematica replacement. We don't translate Mathematica
#     syntax. Callers send native Julia expressions.
#   - A GPU compute engine. Heavy-batch numerical sweeps go to
#     EmergentTerrainGPUEngine, not here.
#   - A sandbox for arbitrary Julia code. The safety filter rejects
#     I/O, package management, process control, native calls, and
#     module loading. Production deployment must still confine the
#     process (systemd / Docker / container).
#
# Lifecycle
# ---------
# The module is loaded once per process start. `start_server!(port)`
# launches the HTTP listener on a daemon thread; it blocks the caller
# until SIGINT/SIGTERM via `wait()`. JIT compilation is warm after the
# first `/eval` of each expression shape — typical warmup ~200ms per
# new function signature, sub-ms after.
# =====================================================================

module JuliaMathSidecar

using HTTP
using JSON3
using Logging

# Public surface — entry points from the module-loader scripts.
export start_server!, start_py_server!

# Internal submodules. Each owns one slice of the HTTP surface.
include("safety.jl")       # Safety filter for incoming expressions.
include("metrics.jl")      # Per-endpoint call counts + latency histogram.
include("numbertheory.jl")    # SPF sieve + window aggregator (phase-28 §28.1).
include("julia_aliases.jl") # Mathematica-flavoured aliases for LLM eval (paper-review 2026-05-22).
include("math_invariants.jl")    # BASE + DERIVED scalar vocabulary (phase-28 §28.4).
include("physics_primitives.jl") # isPrime/isHappy/collatz/sigma/mobius (phase-28 §28.5a).
include("predicate.jl")          # /predicate/scan interpreter (phase-28 §28.5a).
# physics_compute split across one file per JVM domain so each stays
# small and new ports land cleanly. Order matters: per-domain impls
# must be loaded before the dispatch file that references them.
include("physics_mathematics.jl") # MATHEMATICS domain (phase-28 §28.5b/cont/cont2/cont3).
include("physics_physics.jl")    # PHYSICS domain (phase-28 §28.5c).
include("physics_materials.jl")  # MATERIALS domain (phase-28 §28.5d).
include("physics_interdisc.jl")  # INTERDISCIPLINARY domain (phase-28 §28.5e).
include("physics_biology.jl")    # BIOLOGY domain (phase-28 §28.5f).
include("physics_chemistry.jl")  # CHEMISTRY domain (phase-28 §28.5g).
include("physics_arch_terr.jl")  # ARCHITECTURE_TERRESTRIAL domain (phase-28 §28.5h).
include("physics_arch_liquid.jl") # ARCHITECTURE_LIQUID domain (phase-28 §28.5h).
include("physics_arch_space.jl") # ARCHITECTURE_SPACE domain (phase-28 §28.5h).
include("physics.jl")            # physics_compute dispatch (phase-28 §28.5b).
include("conjecture.jl")         # Pearson + tightestKC + seed/update (phase-28 §28.3).
include("handlers.jl")     # Endpoint implementations.
include("server.jl")       # HTTP route table + server loop.

# ---------------------------------------------------------------------
# Precompile workload (Lever B). Bakes the pure-Julia /physics/topic
# compute paths into the package precompile cache so the FIRST call per
# topic shape is already JIT-warm — removing the cold-start cliff where
# an unwarmed topic exceeds the JVM's app.julia.timeoutMs (5s), flips
# JuliaMathClient to "unreachable", and cascades a few fail-fast ticks
# until it warms. Runs during package precompilation (i.e. on the next
# start after any source change — the recompile already happens, this
# just lengthens it once), then every subsequent boot loads warm.
#
# PyCall (/prove_identity, /recognise/constant) and network
# (/recognise/sequence) paths are deliberately NOT here — they can't run
# safely at build time; they stay on the runtime `warmup!` (Lever A).
# ---------------------------------------------------------------------
using PrecompileTools: @setup_workload, @compile_workload

@setup_workload begin
    # Cheap, valid representative inputs. Only argument TYPES and the
    # executed topic branches drive what gets cached — iteration count is
    # irrelevant to what's compiled, so keep every knob TINY. This matters:
    # the workload calls physics_compute directly (no server-side timeout),
    # and some topics (e.g. MATHEMATICS 0/5) run an unbounded search whose
    # cost scales with param1/candidateBreadth — a large value hangs the
    # precompile. searchParams carries only the two keys every impl reads.
    param1 = 4
    param2 = 1.0
    sp = Dict{String,Any}("candidateBreadth" => 2, "stepSize" => 1.0)
    # (domain, topic-range): ranges run one past the last explicit topic so
    # the documented `default`/N+ fall-through branch compiles too.
    workload = [
        ("MATHEMATICS", 0:10), ("PHYSICS", 0:10), ("MATERIALS", 0:10),
        ("INTERDISCIPLINARY", 0:8),
        ("BIOLOGY", 0:5), ("CHEMISTRY", 0:5),
        ("ARCHITECTURE_TERRESTRIAL", 0:5), ("ARCHITECTURE_LIQUID", 0:5),
        ("ARCHITECTURE_SPACE", 0:5),
    ]

    @compile_workload begin
        for (domain, topics) in workload, t in topics
            try
                physics_compute(domain, t, param1, param2, sp)
            catch
                # A topic needing inputs we didn't supply still compiles the
                # path it ran before throwing; never fail precompile over it.
            end
        end
        # Also warm the JVM's real entry shape: JSON body → handler → dispatch.
        try
            req = HTTP.Request("POST", "/physics/topic", [],
                JSON3.write((domain="MATHEMATICS", topicIdx=0,
                             param1=param1, param2=param2, searchParams=sp)))
            handle_physics_topic(req)
        catch
        end
    end
end

end  # module
