# =====================================================================
# server.jl — HTTP server + route table
# =====================================================================
#
# Thin wrapper over HTTP.jl's router. The router dispatches incoming
# requests to one of the `handle_*` functions in `handlers.jl`.
#
# Why HTTP.jl
# -----------
# HTTP.jl is Julia's de facto HTTP library — mature, JSON-friendly,
# supports HTTP/1.1, and the same package can serve and consume. No
# external runtime dependencies (no nginx, no envoy) — just `julia`.
#
# Threading model
# ---------------
# HTTP.jl serves each connection on a separate task. The `Threads.@spawn`
# inside `timed_evaluate` is a *different* task from the connection
# task — long-running math doesn't block other incoming requests. For
# CPU-bound parallelism, start Julia with `JULIA_NUM_THREADS=N`.
# =====================================================================

using HTTP
using Logging

# Build the route table. Each route binds a method+path pair to one
# of the handlers defined in handlers.jl.
function build_router()
    router = HTTP.Router()
    # Liveness / readiness.
    HTTP.register!(router, "GET",  "/healthz",  handle_healthz)
    # Metrics surface — call counts + latency per endpoint.
    HTTP.register!(router, "GET",  "/metrics",  handle_metrics)
    # Core evaluation endpoints.
    HTTP.register!(router, "POST", "/eval",      handle_eval)
    HTTP.register!(router, "POST", "/solve",     handle_solve)
    HTTP.register!(router, "POST", "/nsolve",    handle_nsolve)
    HTTP.register!(router, "POST", "/integrate", handle_integrate)
    HTTP.register!(router, "POST", "/sweep",     handle_sweep)
    # Symbolic regression — PySR-equivalent via SymbolicRegression.jl
    # (phase-24 §24.1). Gated by per-call lock; one search at a time.
    HTTP.register!(router, "POST", "/pysr",      handle_pysr)
    # SymPy Gosper / WZ identity proofs via PyCall.jl (phase-25 §25.1).
    # Per-call lock — PyCall + GIL means concurrent calls already
    # serialise; the explicit lock gives a deterministic 503 surface.
    HTTP.register!(router, "POST", "/prove_identity", handle_prove_identity)
    # Number-theory window aggregator — port of `MathConjectureSubstrate`
    # (phase-28 §28.1). Numeric input only; no safety filter exposure.
    HTTP.register!(router, "POST", "/numbertheory/window", handle_numbertheory_window)
    # OEIS Superseeker lookup (phase-26 §26.1). JVM caller is expected to
    # maintain a Postgres cache; the sidecar only sees cache misses.
    HTTP.register!(router, "POST", "/recognise/sequence", handle_recognise_sequence)
    # PSLQ integer-relation search (phase-26 §26.2). PyCall→mpmath at
    # caller-configured precision against named basis sets.
    HTTP.register!(router, "POST", "/recognise/constant", handle_recognise_constant)
    # Minimal polynomial recovery (phase-26 §26.3). PSLQ on the polynomial
    # basis [1, α, α², ..., α^d] — equivalent to single-row LLL for this
    # narrow use case; no Nemo.jl dep required.
    HTTP.register!(router, "POST", "/recognise/minpoly", handle_recognise_minpoly)
    # Dalmatian-style conjecture seed/update (phase-28 §28.3). Pure numeric
    # compute; the §28.3 parity test pins byte-identical output against the
    # JVM ConjectureEngine.
    HTTP.register!(router, "POST", "/conjecture/seed",   handle_conjecture_seed)
    HTTP.register!(router, "POST", "/conjecture/update", handle_conjecture_update)
    return router
end

"""
    start_server!(; host="127.0.0.1", port=8088)

Block the calling task and serve HTTP requests until SIGINT/SIGTERM.

Defaults to 127.0.0.1 — the sidecar should never expose itself on a
public interface. Production deployment binds to a Unix socket or
keeps it localhost-only behind a reverse proxy.

JIT warmup: the first request to each shape (e.g. the first `/eval`,
the first `/solve`) takes 200ms-2s while Julia compiles the call path.
Subsequent calls are sub-millisecond. Run `scripts/warmup.jl` against
a freshly-started server if you want to pre-warm common paths before
real traffic hits.
"""
function start_server!(; host::AbstractString="127.0.0.1", port::Integer=8088)
    init_metrics!()
    router = build_router()
    @info "julia-math-sidecar starting" host port julia_version=string(VERSION)
    # `server=` is the listener — assigning to a local lets us close
    # cleanly on signal. `HTTP.serve!` (note the bang) is the
    # non-blocking form that returns the server immediately; we then
    # `wait(server)` so the script blocks until the server exits.
    server = HTTP.serve!(router, host, port)
    try
        # Block until the listener task exits (SIGINT or explicit close).
        wait(server)
    catch e
        if e isa InterruptException
            @info "received SIGINT, shutting down"
        else
            rethrow()
        end
    finally
        close(server)
    end
    return nothing
end
