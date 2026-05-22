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

# Public surface — entry point from the module-loader script.
export start_server!

# Internal submodules. Each owns one slice of the HTTP surface.
include("safety.jl")       # Safety filter for incoming expressions.
include("metrics.jl")      # Per-endpoint call counts + latency histogram.
include("numbertheory.jl")    # SPF sieve + window aggregator (phase-28 §28.1).
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
include("physics.jl")            # physics_compute dispatch (phase-28 §28.5b).
include("conjecture.jl")         # Pearson + tightestKC + seed/update (phase-28 §28.3).
include("handlers.jl")     # Endpoint implementations.
include("server.jl")       # HTTP route table + server loop.

end  # module
