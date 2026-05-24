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
    # PyCall-backed endpoints (sympy/mpmath) are unsafe in THIS process under
    # JULIA_NUM_THREADS>1 (off-thread pydecref → SIGSEGV). When
    # PY_SIDECAR_ENABLED=true they proxy to the single-threaded py-sidecar
    # (scripts/start-py-sidecar.sh); default false → served in-process as before.
    py_proxy = get(ENV, "PY_SIDECAR_ENABLED", "false") == "true"
    # Route the PyCall endpoints safely. They segfault in-process under >1 thread
    # (Julia GC runs pydecref off the GIL-holding thread), so:
    #   PY_SIDECAR_ENABLED=true   → proxy to the single-threaded py-sidecar
    #   single-threaded            → serve in-process (safe)
    #   multithreaded + no proxy   → FAIL SAFE with 503, rather than load PyCall
    #                                here and crash the whole process.
    pyroute(path, h) =
        if py_proxy
            (req::HTTP.Request -> proxy_to_py(req, path))
        elseif Threads.nthreads() == 1
            h
        else
            (req::HTTP.Request -> py_unavailable_response(path))
        end
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
    HTTP.register!(router, "POST", "/prove_identity", pyroute("/prove_identity", handle_prove_identity))
    # Number-theory window aggregator — port of `MathConjectureSubstrate`
    # (phase-28 §28.1). Numeric input only; no safety filter exposure.
    HTTP.register!(router, "POST", "/numbertheory/window", handle_numbertheory_window)
    # OEIS Superseeker lookup (phase-26 §26.1). JVM caller is expected to
    # maintain a Postgres cache; the sidecar only sees cache misses.
    HTTP.register!(router, "POST", "/recognise/sequence", handle_recognise_sequence)
    # PSLQ integer-relation search (phase-26 §26.2). PyCall→mpmath at
    # caller-configured precision against named basis sets.
    HTTP.register!(router, "POST", "/recognise/constant", pyroute("/recognise/constant", handle_recognise_constant))
    # Minimal polynomial recovery (phase-26 §26.3). PSLQ on the polynomial
    # basis [1, α, α², ..., α^d] — equivalent to single-row LLL for this
    # narrow use case; no Nemo.jl dep required.
    HTTP.register!(router, "POST", "/recognise/minpoly", pyroute("/recognise/minpoly", handle_recognise_minpoly))
    # Dalmatian-style conjecture seed/update (phase-28 §28.3). Pure numeric
    # compute; the §28.3 parity test pins byte-identical output against the
    # JVM ConjectureEngine.
    HTTP.register!(router, "POST", "/conjecture/seed",   handle_conjecture_seed)
    HTTP.register!(router, "POST", "/conjecture/update", handle_conjecture_update)
    # Held-out verification of an Inequality across N disjoint windows
    # (phase-28 §28.4). Batches what would otherwise be N round-trips
    # to /numbertheory/window into one call; the JVM ConjectureVerifier
    # routes here when the conjecture flag is on.
    HTTP.register!(router, "POST", "/conjecture/verify", handle_conjecture_verify)
    # DynamicComputeEngine port — predicate / metric scan over an
    # integer range (phase-28 §28.5a). Returns numeric scalars only;
    # the JVM caller layers on the prose. /physics/topic is deferred
    # to §28.5b — 3700-line port too large for one slice.
    HTTP.register!(router, "POST", "/predicate/scan", handle_predicate_scan)
    # MathPhysicsEngine.compute vertical-slice port (phase-28 §28.5b).
    # Only MATHEMATICS topic 0 (primeConstellations) ported in this
    # slice; other (domain, topicIdx) pairs return ok=false with
    # error="not_ported" and the JVM client falls back to in-process
    # legacy. Future slices extend the physics_compute dispatch.
    HTTP.register!(router, "POST", "/physics/topic", handle_physics_topic)
    return router
end

"""
    start_server!(; host="127.0.0.1", port=8088)

Block the calling task and serve HTTP requests until SIGINT/SIGTERM.

Defaults to 127.0.0.1 — the sidecar should never expose itself on a
public interface. Production deployment binds to a Unix socket or
keeps it localhost-only behind a reverse proxy.

JIT warmup: the first request to each shape (e.g. the first `/eval`,
the first `/solve`) takes 200ms-2s while Julia compiles the call path;
subsequent calls are sub-millisecond. Once the listener is up we call
`warmup!`, which pre-pays the outbound-HTTP client path
(`/recognise/sequence`). Pure-Julia compute paths are baked into the
precompile cache via PrecompileTools; PyCall endpoints are isolated to the
single-threaded py-sidecar (see `start_py_server!`).
"""
function start_server!(; host::AbstractString="127.0.0.1", port::Integer=8088)
    init_metrics!()
    router = build_router()
    @info "julia-math-sidecar starting" host port julia_version=string(VERSION) cpu_threads=SIDECAR_CPU_THREADS julia_threads=Threads.nthreads()
    # `server=` is the listener — assigning to a local lets us close
    # cleanly on signal. `HTTP.serve!` (note the bang) is the
    # non-blocking form that returns the server immediately; we then
    # `wait(server)` so the script blocks until the server exits.
    server = HTTP.serve!(router, host, port)
    # Listener is accepting now; pre-pay the first-call costs precompile
    # can't cover (PyCall imports + outbound-HTTP). Best-effort and
    # synchronous on this task — with ≥2 threads the server keeps serving
    # /healthz on other threads while this runs, and delaying `wait` has no
    # effect since the listener task is already independent. See `warmup!`.
    warmup!(; host=host, port=port)
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

"""
    warmup!(; host, port)

Best-effort startup warmup for the outbound-HTTP client path
(`/recognise/sequence`'s OEIS fetch), compiled via a loopback GET to
`/healthz` so OEIS is never hit at boot.

PyCall warmup (SymPy/mpmath) was removed: under JULIA_NUM_THREADS>1 it left
PyObjects that crash in off-thread GC finalizers during /pysr (see note in
the body). The step is isolated in try/catch — a failure (loopback not yet
accepting, etc.) is logged and skipped, never fatal. Set `SIDECAR_WARMUP=0`
to skip. Pure-Julia compute paths are baked in via PrecompileTools instead.
"""
function warmup!(; host::AbstractString, port::Integer)
    if get(ENV, "SIDECAR_WARMUP", "1") != "1"
        @info "warmup skipped (SIDECAR_WARMUP=0)"
        return nothing
    end
    t0 = time_ns()
    @info "warmup starting (outbound-HTTP path)"

    # NOTE (2026-05-24): PyCall imports (SymPy/mpmath) were intentionally REMOVED
    # from warmup. With JULIA_NUM_THREADS>1, eagerly loading PyCall left live
    # PyObjects that Julia's GC could finalize (pydecref → _Py_Dealloc) on a
    # non-main worker thread during a later multithreaded /pysr run → SIGSEGV
    # (observed crash). Keeping the server PyCall-free at startup means a sidecar
    # doing only /pysr + physics never touches Python. The PyCall endpoints
    # (/prove_identity, /recognise/constant) lazy-load Python on first use and
    # carry a documented threads-vs-PyCall hazard until they're isolated.

    # Outbound HTTP client — what /recognise/sequence pays on its first OEIS
    # fetch. Compile HTTP.get + response handling via a loopback GET to our
    # own /healthz so we never touch OEIS at boot. 0.0.0.0 is a bind address,
    # not a connect target, so dial 127.0.0.1 when bound to all interfaces.
    try
        connect_host = host == "0.0.0.0" ? "127.0.0.1" : host
        HTTP.get("http://$(connect_host):$(port)/healthz";
                 readtimeout=2, retry=false, status_exception=false)
    catch e
        @warn "warmup: HTTP-client warmup skipped" error=sprint(showerror, e)
    end

    @info "warmup complete" elapsed_ms=round(Int, (time_ns() - t0) / 1_000_000)
    return nothing
end

# ── PyCall isolation (segfault fix) ─────────────────────────────────────
# PyCall's pydecref finalizer crashes when Julia's GC runs it off the
# GIL-holding thread, which happens under JULIA_NUM_THREADS>1. Rather than
# give up the multithreading the main server needs, the PyCall endpoints are
# moved to a single-threaded companion process (start_py_server!) and the main
# server proxies to it (gated by PY_SIDECAR_ENABLED, default off).

"""
    proxy_to_py(req, path) -> HTTP.Response

Forward a PyCall-backed request to the py-sidecar (`PY_SIDECAR_URL`, default
http://127.0.0.1:8089) and relay its response verbatim. Used by `build_router`
only when `PY_SIDECAR_ENABLED=true`. Returns 503 if the py-sidecar is down.
"""
function proxy_to_py(req::HTTP.Request, path::AbstractString)
    base = get(ENV, "PY_SIDECAR_URL", "http://127.0.0.1:8089")
    try
        resp = HTTP.post(string(base, path);
                         body = req.body,
                         headers = ["Content-Type" => "application/json"],
                         readtimeout = 120, retry = false, status_exception = false)
        return HTTP.Response(resp.status, ["Content-Type" => "application/json"]; body = resp.body)
    catch e
        @warn "py-sidecar proxy failed" path baseUrl=base error=sprint(showerror, e)
        return HTTP.Response(503, ["Content-Type" => "application/json"];
                             body = "{\"ok\":false,\"error\":\"py_sidecar_unreachable\"}")
    end
end

"""
    py_unavailable_response(path) -> HTTP.Response

503 returned for a PyCall-backed route when the main (multithreaded) server is
asked to serve it in-process with the py-sidecar disabled. Refusing here is the
safe choice: loading PyCall in a >1-thread process risks an off-thread
`pydecref` finalizer SIGSEGV that takes down the whole server.
"""
function py_unavailable_response(path::AbstractString)
    @warn "PyCall endpoint refused in-process (multithreaded, PY_SIDECAR_ENABLED=false)" path nthreads=Threads.nthreads()
    body = string("{\"ok\":false,\"error\":\"pycall_endpoint_requires_py_sidecar\",\"path\":\"",
                  path,
                  "\",\"detail\":\"PyCall endpoints are unsafe in the multithreaded server; ",
                  "start scripts/start-py-sidecar.sh and restart with PY_SIDECAR_ENABLED=true\"}")
    return HTTP.Response(503, ["Content-Type" => "application/json"]; body = body)
end

# Minimal router for the py-sidecar: only the PyCall-backed routes (served
# LOCALLY here — this IS the PyCall process) plus liveness/metrics. Never
# proxies, so it can't loop back on itself.
function build_py_router()
    router = HTTP.Router()
    HTTP.register!(router, "GET",  "/healthz", handle_healthz)
    HTTP.register!(router, "GET",  "/metrics", handle_metrics)
    HTTP.register!(router, "POST", "/prove_identity",     handle_prove_identity)
    HTTP.register!(router, "POST", "/recognise/constant", handle_recognise_constant)
    HTTP.register!(router, "POST", "/recognise/minpoly",  handle_recognise_minpoly)
    return router
end

"""
    start_py_server!(; host="127.0.0.1", port=8089)

Companion process that owns ALL PyCall (sympy/mpmath) work, serving only the
PyCall-backed endpoints. MUST run single-threaded (`JULIA_NUM_THREADS=1`, via
scripts/start-py-sidecar.sh): PyCall's `pydecref` finalizers segfault when run
off the GIL-holding thread, which is exactly what Julia's GC does under >1
thread. With one thread every finalizer runs on it → safe. The main sidecar
(4 threads) stays PyCall-free and proxies here when PY_SIDECAR_ENABLED=true.
"""
function start_py_server!(; host::AbstractString="127.0.0.1", port::Integer=8089)
    if Threads.nthreads() > 1
        @warn "start_py_server!: >1 thread is UNSAFE for PyCall (off-thread \
               pydecref segfaults). Start with JULIA_NUM_THREADS=1." nthreads=Threads.nthreads()
    end
    init_metrics!()
    router = build_py_router()
    @info "julia-math-sidecar PY-SIDECAR starting" host port julia_threads=Threads.nthreads()
    # Warm sympy + mpmath now — safe here because single-threaded. (This is the
    # warmup that had to be removed from the main server.)
    try
        get_sympy()
        pyimport("mpmath")
        @info "py-sidecar: PyCall (sympy + mpmath) warmed"
    catch e
        @warn "py-sidecar: PyCall warmup skipped" error=sprint(showerror, e)
    end
    server = HTTP.serve!(router, host, port)
    try
        wait(server)
    catch e
        if e isa InterruptException
            @info "py-sidecar received SIGINT, shutting down"
        else
            rethrow()
        end
    finally
        close(server)
    end
    return nothing
end
