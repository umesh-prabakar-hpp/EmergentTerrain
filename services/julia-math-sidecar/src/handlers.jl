# =====================================================================
# handlers.jl — HTTP endpoint implementations
# =====================================================================
#
# Each handler:
#   1. Parses the JSON body into a typed request shape.
#   2. Runs the safety filter on any expression text (`safety_check`).
#   3. Evaluates inside a `timed_evaluate` wrapper that:
#       - Spawns a worker task with `Threads.@spawn`
#       - Races it against a `sleep(timeoutMs)` task
#       - First-completed-wins; the loser is logged but Julia has no
#         clean cancellation primitive so a runaway task can outlive
#         the response. Mitigation: process-wide watchdog (future).
#   4. Records the result + latency via `record_call!`.
#   5. Returns a uniform JSON response envelope:
#         {"ok": true,  "result": ..., "elapsed_ms": ...}
#         {"ok": false, "error": "...", "explanation": "...", ...}
#
# Endpoint shape contracts
# ------------------------
# All POST bodies are JSON. All responses are JSON with at minimum
# `{ok, elapsed_ms}` plus endpoint-specific fields. Errors carry an
# `error` enum code (`safety_rejected`, `parse_error`, `timeout`,
# `eval_error`, `bad_request`) so callers can branch deterministically.
# =====================================================================

using HTTP
using JSON3
using Logging
using Symbolics
using Roots
using QuadGK
using SymbolicRegression
using Statistics: median, mean
using PyCall

# ---------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------

# Default per-call timeout in milliseconds. Phase-22 §22.5 calls for a
# `app.julia.maxEvalMs` knob on the EmergentTerrain side; the sidecar
# enforces its own ceiling on top, so a buggy client can't ask for a
# 60-second hang. The /pysr endpoint runs symbolic regression that
# legitimately wants a longer search budget, so it has its own larger
# ceiling (`MAX_PYSR_TIMEOUT_MS`, defined near the /pysr handler).
const DEFAULT_TIMEOUT_MS = 5_000
const MAX_TIMEOUT_MS     = 30_000   # absolute ceiling for /eval, /solve, /sweep, etc.

# Helper: pull the request timeout out of a parsed JSON body, clamped
# to the (0, MAX_TIMEOUT_MS] range. Missing or invalid → default.
function clamp_timeout_ms(body)::Int
    v = get(body, :timeoutMs, nothing)
    if v isa Integer && v > 0
        return min(v, MAX_TIMEOUT_MS)
    end
    return DEFAULT_TIMEOUT_MS
end

# Helper: build the success-response envelope. Endpoint adds its own
# fields via `extra`.
function ok_response(elapsed_ms::Int, extra::Dict)
    merged = merge(Dict("ok" => true, "elapsed_ms" => elapsed_ms), extra)
    return HTTP.Response(200, ["Content-Type" => "application/json"],
                         JSON3.write(merged))
end

# Helper: build an error-response envelope. HTTP status determined by
# the error code.
function error_response(code::String, explanation::String, elapsed_ms::Int;
                        status::Int=400, extra::Dict=Dict())
    body = merge(Dict(
        "ok" => false,
        "error" => code,
        "explanation" => explanation,
        "elapsed_ms" => elapsed_ms,
    ), extra)
    return HTTP.Response(status, ["Content-Type" => "application/json"],
                         JSON3.write(body))
end

# Helper: safely parse the JSON body. Returns `nothing` on malformed
# JSON so handlers can return a clean 400 instead of throwing.
function safe_parse_body(req::HTTP.Request)
    try
        return JSON3.read(req.body)
    catch e
        @warn "JSON parse failed" exception=(e, catch_backtrace())
        return nothing
    end
end

# ---------------------------------------------------------------------
# Timed evaluation wrapper
# ---------------------------------------------------------------------
#
# Julia has no first-class task cancellation. The best we can do is
# spawn the work on a separate task and race it against a sleep. If
# the sleep wins, we mark the call as `:timeout` and return — the
# worker task continues running in the background. For long-running
# math (Symja's `Sum[1, {k, Infinity}]` equivalent) this can leak
# until the worker eventually completes or the process restarts.
#
# Production deployment should pair this with:
#   - A process-wide memory cap (systemd `MemoryMax=512M` / Docker
#     `--memory=512m`) so leaked tasks can't OOM the host.
#   - A periodic process restart (daily) to clear leaked workers.
#
# Returns `(outcome, value)` where outcome ∈ {:ok, :timeout, :error}.

function timed_evaluate(f::Function, timeout_ms::Int)
    result_ch = Channel{Tuple{Symbol,Any}}(1)
    # Worker task — runs the user function, captures result or error.
    Threads.@spawn try
        v = f()
        put!(result_ch, (:ok, v))
    catch e
        # Capture the full error info so the handler can include it.
        # We don't include the stack trace in the response — that
        # would leak file paths. Logged server-side for debugging.
        @warn "evaluator threw" exception=(e, catch_backtrace())
        put!(result_ch, (:error, sprint(showerror, e)))
    end
    # Timeout task — races against the worker.
    Threads.@spawn begin
        sleep(timeout_ms / 1000)
        # Only signal timeout if no other writer beat us to it.
        # Channel{T}(1) semantics: put! blocks when full, so we
        # check isfull first.
        if !isready(result_ch)
            put!(result_ch, (:timeout, "exceeded $(timeout_ms)ms"))
        end
    end
    return take!(result_ch)
end

# ---------------------------------------------------------------------
# /healthz — readiness probe
# ---------------------------------------------------------------------
#
# Simple liveness check. Returns 200 + uptime as soon as the server
# is up. EmergentTerrain's JuliaMathClient calls this at boot to
# decide whether to enable the Julia path.

function handle_healthz(req::HTTP.Request)
    t0 = time_ns()
    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    record_call!("healthz", elapsed_ms, :ok)
    return ok_response(elapsed_ms, Dict(
        "service" => "julia-math-sidecar",
        "status"  => "ready",
    ))
end

# ---------------------------------------------------------------------
# /metrics — counters + uptime
# ---------------------------------------------------------------------

function handle_metrics(req::HTTP.Request)
    t0 = time_ns()
    snap = metrics_snapshot()
    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    return HTTP.Response(200, ["Content-Type" => "application/json"],
                         JSON3.write(merge(Dict("ok" => true,
                                                "elapsed_ms" => elapsed_ms),
                                            snap)))
end

# ---------------------------------------------------------------------
# /eval — arbitrary Julia expression evaluation
# ---------------------------------------------------------------------
#
# Body: `{"expr": "<julia expr>", "timeoutMs": optional}`
# Response on success: `{"ok": true, "result": <value>, "type": "<type>", "elapsed_ms": N}`
#
# Examples:
#   {"expr": "floor(2.04e-6 / 400e-9)"}      → result=5,    type="Float64"
#   {"expr": "sin(π/4)"}                      → result=0.7071…, type="Float64"
#   {"expr": "factorial(10)"}                 → result=3628800,  type="Int64"
#   {"expr": "[i^2 for i in 1:5]"}            → result=[1,4,9,16,25], type="Vector{Int64}"
#
# The result is `string(value)` rather than a structured JSON of the
# value's tree — Julia objects don't all have a clean JSON shape, and
# the LLM caller just wants the textual form for parsing back.

function handle_eval(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("eval", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    expr = get(body, :expr, "")
    if !(expr isa AbstractString) || isempty(expr)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("eval", elapsed_ms, :error)
        return error_response("bad_request", "Missing required field 'expr' (non-empty string)", elapsed_ms)
    end

    # Safety filter — short-circuit before parsing.
    reject = safety_reject_response(expr)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("eval", elapsed_ms, :safety_reject)
        return reject
    end

    timeout_ms = clamp_timeout_ms(body)

    # Parse + evaluate inside the timed wrapper. `Meta.parse` runs first
    # and fails fast on syntactically-invalid input; the eval itself
    # runs inside the spawned task.
    parsed = try
        Meta.parse(expr)
    catch e
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("eval", elapsed_ms, :error)
        return error_response("parse_error", sprint(showerror, e), elapsed_ms)
    end

    outcome, value = timed_evaluate(timeout_ms) do
        # `Main.eval` runs in the toplevel module — same scope as the
        # REPL. Bindings created here persist for the process lifetime.
        # Acceptable for a math sidecar (no user-visible side effects);
        # would be a bug for a multi-tenant service.
        Main.eval(parsed)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("eval", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("eval", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("eval", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "result" => string(value),
            "type"   => string(typeof(value)),
        ))
    end
end

# ---------------------------------------------------------------------
# /solve — symbolic equation solving
# ---------------------------------------------------------------------
#
# Body: `{"expr": "x^2 - 4 == 0", "var": "x", "timeoutMs": optional}`
#       OR `{"expr": "x^2 - 4", "var": "x"}`  (LHS=0 implicit)
# Response on success: `{"ok": true, "result": ["...", "..."], "elapsed_ms": N}`
#
# Returns symbolic roots as an array of stringified solutions. If the
# system can't crack it symbolically, the response carries
# `"symbolic_failed": true` and the caller should retry against
# `/nsolve` with an initial guess.

function handle_solve(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("solve", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    expr_str = get(body, :expr, "")
    var_str  = get(body, :var, "x")

    if !(expr_str isa AbstractString) || isempty(expr_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("solve", elapsed_ms, :error)
        return error_response("bad_request", "Missing required field 'expr'", elapsed_ms)
    end

    reject = safety_reject_response(expr_str)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("solve", elapsed_ms, :safety_reject)
        return reject
    end

    timeout_ms = clamp_timeout_ms(body)

    outcome, value = timed_evaluate(timeout_ms) do
        # We build a small Symbolics.jl variable + expression. The
        # `@variables` macro is what users typically write at the REPL;
        # here we construct it programmatically with `eval`.
        sym = Symbolics.variable(Symbol(var_str))
        # Allow either "x^2 - 4 == 0" or just "x^2 - 4" — strip an
        # explicit "== 0" if present, since Symbolics' symbolic_solve
        # expects an equation form `expr ~ 0` internally.
        cleaned = replace(expr_str, "== 0" => "", "==0" => "")
        # Bind the user's variable name into the parse scope so
        # `Meta.parse(cleaned)` resolves it. The local `sym` variable
        # is referenced by name via the variable's Symbol.
        Main.eval(:($(Symbol(var_str)) = $sym))
        rhs = Main.eval(Meta.parse(cleaned))
        # symbolic_solve returns a Vector{Any} of root expressions, or
        # nothing if it can't solve symbolically.
        roots = Symbolics.symbolic_solve(rhs ~ 0, sym)
        if roots === nothing
            return (:symbolic_failed, nothing)
        end
        return (:roots, [string(r) for r in roots])
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("solve", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("solve", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok with embedded (status, payload)
        status_sym, payload = value
        record_call!("solve", elapsed_ms, :ok)
        if status_sym === :symbolic_failed
            return ok_response(elapsed_ms, Dict(
                "symbolic_failed" => true,
                "hint" => "Symbolics.jl could not solve symbolically. " *
                          "Try /nsolve with an initial guess for a numeric root.",
            ))
        else
            return ok_response(elapsed_ms, Dict("result" => payload))
        end
    end
end

# ---------------------------------------------------------------------
# /nsolve — numeric root finding from initial guess
# ---------------------------------------------------------------------
#
# Body: `{"expr": "x^2 - 4", "var": "x", "x0": 1.0, "timeoutMs": optional}`
# Response: `{"ok": true, "result": <Float64>, "elapsed_ms": N}`
#
# Uses `Roots.find_zero` which auto-selects bracketing/non-bracketing
# algorithms based on the input shape. `x0` may be a single value
# (Newton-class) or a 2-tuple `[lo, hi]` (bisection-class).

function handle_nsolve(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("nsolve", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    expr_str = get(body, :expr, "")
    var_str  = get(body, :var, "x")
    x0       = get(body, :x0, nothing)

    if !(expr_str isa AbstractString) || isempty(expr_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("nsolve", elapsed_ms, :error)
        return error_response("bad_request", "Missing required field 'expr'", elapsed_ms)
    end
    if x0 === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("nsolve", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required field 'x0' (initial guess or [lo, hi] bracket)",
            elapsed_ms)
    end

    reject = safety_reject_response(expr_str)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("nsolve", elapsed_ms, :safety_reject)
        return reject
    end

    timeout_ms = clamp_timeout_ms(body)

    outcome, value = timed_evaluate(timeout_ms) do
        # Build a one-arg function `f(x) = expr_with_x_substituted`.
        # `Meta.parse` + `:(x -> $parsed)` produces an anonymous-fn AST
        # that we then eval to get an actual callable.
        parsed = Meta.parse(expr_str)
        fn = Main.eval(:($(Symbol(var_str)) -> $parsed))
        # Convert x0 to the right type for Roots.find_zero.
        x0_typed = x0 isa AbstractArray ? Float64.(x0) : Float64(x0)
        # `Base.invokelatest` sidesteps Julia's world-age trap: the
        # `fn` we just `Main.eval`-ed is "newer" than the surrounding
        # task's world, so a direct `Roots.find_zero(fn, x0)` would
        # raise `MethodError: ... method too new to be called from
        # this world context`. invokelatest dispatches in the current
        # world. Repeated below in /integrate and /sweep handlers.
        Base.invokelatest(Roots.find_zero, fn, x0_typed)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("nsolve", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("nsolve", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("nsolve", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict("result" => value))
    end
end

# ---------------------------------------------------------------------
# /integrate — numeric definite integration via QuadGK
# ---------------------------------------------------------------------
#
# Body: `{"expr": "sin(x)", "var": "x", "a": 0.0, "b": π, "timeoutMs": optional}`
# Response: `{"ok": true, "result": <Float64>, "error_estimate": <Float64>, "elapsed_ms": N}`
#
# Note: this is the **numeric** integral, not symbolic antiderivative.
# Symbolic integration would need Symbolics.jl's integrate (limited)
# or SymPy.jl (deferred per phase-22 §22.1). For most physics uses
# the numeric definite integral is what's wanted.

function handle_integrate(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("integrate", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    expr_str = get(body, :expr, "")
    var_str  = get(body, :var, "x")
    a_raw    = get(body, :a, nothing)
    b_raw    = get(body, :b, nothing)

    if !(expr_str isa AbstractString) || isempty(expr_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("integrate", elapsed_ms, :error)
        return error_response("bad_request", "Missing required field 'expr'", elapsed_ms)
    end
    if a_raw === nothing || b_raw === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("integrate", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required fields 'a' / 'b' (integration limits)",
            elapsed_ms)
    end

    reject = safety_reject_response(expr_str)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("integrate", elapsed_ms, :safety_reject)
        return reject
    end

    timeout_ms = clamp_timeout_ms(body)

    outcome, value = timed_evaluate(timeout_ms) do
        # Parse limits — accept either numeric or string literals like
        # "π" / "pi". `Meta.parse` handles both.
        a = a_raw isa Number ? Float64(a_raw) : Float64(Main.eval(Meta.parse(string(a_raw))))
        b = b_raw isa Number ? Float64(b_raw) : Float64(Main.eval(Meta.parse(string(b_raw))))
        parsed = Meta.parse(expr_str)
        fn = Main.eval(:($(Symbol(var_str)) -> $parsed))
        # QuadGK returns (value, error_estimate). We return both — the
        # error estimate is useful for the caller to decide whether to
        # tighten tolerances or accept the result. `invokelatest`
        # dispatches in the current world — see /nsolve handler for why.
        Base.invokelatest(QuadGK.quadgk, fn, a, b)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("integrate", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("integrate", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        result, err = value
        record_call!("integrate", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "result" => result,
            "error_estimate" => err,
        ))
    end
end

# ---------------------------------------------------------------------
# /sweep — evaluate expression across a range, return stats
# ---------------------------------------------------------------------
#
# Body: `{"expr": "x^2", "var": "x", "start": 1, "end": 100, "samples": 100,
#          "timeoutMs": optional}`
# Response: `{"ok": true, "mean": ..., "std": ..., "min": ..., "max": ...,
#             "n": ..., "elapsed_ms": N}`
#
# Mirrors EmergentTerrain's `evaluate_symbolic` tool: take an
# expression in one variable, evaluate it over a uniform grid, return
# summary statistics. Doesn't return the per-sample values (would
# bloat the response); callers wanting full sample data should use
# /eval with a comprehension.

function handle_sweep(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("sweep", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    expr_str = get(body, :expr, "")
    var_str  = get(body, :var, "x")
    start_v  = get(body, :start, nothing)
    end_v    = get(body, :end, nothing)
    samples  = get(body, :samples, 100)

    if !(expr_str isa AbstractString) || isempty(expr_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("sweep", elapsed_ms, :error)
        return error_response("bad_request", "Missing required field 'expr'", elapsed_ms)
    end
    if start_v === nothing || end_v === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("sweep", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required fields 'start' / 'end'",
            elapsed_ms)
    end
    if !(samples isa Integer) || samples <= 0 || samples > 100_000
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("sweep", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'samples' must be an integer in (0, 100_000]",
            elapsed_ms)
    end

    reject = safety_reject_response(expr_str)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("sweep", elapsed_ms, :safety_reject)
        return reject
    end

    timeout_ms = clamp_timeout_ms(body)

    outcome, value = timed_evaluate(timeout_ms) do
        parsed = Meta.parse(expr_str)
        fn = Main.eval(:($(Symbol(var_str)) -> $parsed))
        a = Float64(start_v); b = Float64(end_v)
        # Uniform grid of `samples` points across [a, b].
        xs = collect(range(a, b, length=samples))
        # `invokelatest` because `fn` was just eval'd — see /nsolve.
        ys = [Float64(Base.invokelatest(fn, x)) for x in xs]
        n = length(ys)
        mu = sum(ys) / n
        # Population std (denom=n). Sufficient for diagnostic stats.
        variance = sum((y - mu)^2 for y in ys) / n
        sigma = sqrt(variance)
        (mean=mu, std=sigma, min=minimum(ys), max=maximum(ys), n=n)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("sweep", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("sweep", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("sweep", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "mean" => value.mean,
            "std"  => value.std,
            "min"  => value.min,
            "max"  => value.max,
            "n"    => value.n,
        ))
    end
end

# ---------------------------------------------------------------------
# /pysr — symbolic regression via SymbolicRegression.jl  (phase-24 §24.1)
# ---------------------------------------------------------------------
#
# Body:
#   {
#     "samples":          [[x1, y1, …, target1], …],   <= 256 rows
#     "varNames":         ["x", "y", …],                 <= 6 names
#     "targetName":       "f",
#     "operators":        ["+","-","*","/","^","exp","log",…]?,
#     "populations":      8?,
#     "iterations":       40?,
#     "complexityCeiling": 30?,
#     "seed":             <int>?,
#     "timeoutMs":        20000?
#   }
#
# Response (success):
#   {
#     "ok": true,
#     "pareto": [
#       {"expr": "<julia expr>", "mse": <f64>, "complexity": <int>},
#       …
#     ],
#     "evaluated": <int>,
#     "elapsed_ms": <int>
#   }
#
# Why a separate endpoint
# -----------------------
# /eval and /sweep evaluate a known expression. /pysr searches *for* an
# expression that fits supplied data — fundamentally a different shape.
# SymbolicRegression.jl is a heavy dep (~150 MB compiled state), so the
# import lives at the top of this file but the search is gated behind
# this endpoint only.
#
# Concurrency
# -----------
# SymbolicRegression.jl shares mutable per-population state internally
# and is not safe to invoke from two HTTP handler threads at once. The
# `PYSR_LOCK` reentrant lock serialises calls; a second concurrent
# /pysr call blocks up to `queueMs` (clamped against the per-call
# timeout) before returning `busy`. The Java client surfaces `busy`
# as a typed exception so the LLM tool loop can retry or move on.

# Default per-call params for /pysr. Sized for a 20-second wall-clock
# budget — enough to recover laws like F = G m1 m2 / r² but not enough
# to chase arbitrary high-complexity fits. The Java side overrides
# these via the request body when needed.
const DEFAULT_PYSR_TIMEOUT_MS    = 20_000
const DEFAULT_PYSR_POPULATIONS   = 8
const DEFAULT_PYSR_ITERATIONS    = 40
const DEFAULT_PYSR_MAX_COMPLEXITY = 30
const MAX_PYSR_SAMPLES = 256
const MAX_PYSR_VARS    = 6
const MAX_PYSR_QUEUE_MS = 5_000   # how long a second call waits behind the lock
# Per-endpoint ceiling: SR runs evolve a Pareto front rather than evaluate a
# single expression, so they need a budget well above the 30s shared cap.
# PYSR_LOCK serialises calls, so a long search can only tie up one core at a
# time; the daily restart referenced in `timed_evaluate` still bounds leaks.
const MAX_PYSR_TIMEOUT_MS = 120_000

const PYSR_LOCK = ReentrantLock()

# Map operator strings (validated by the safety allowlist) to the actual
# Julia callables SymbolicRegression.Options expects. Kept in lockstep
# with `ALLOWED_PYSR_OPERATORS` — adding a new operator means an entry
# both there and in one of these tables.
const PYSR_BINARY_OPS = Dict(
    "+" => (+),
    "-" => (-),
    "*" => (*),
    "/" => (/),
    "^" => (^),
)
const PYSR_UNARY_OPS = Dict(
    "exp"  => exp,
    "log"  => log,
    "sqrt" => sqrt,
    "abs"  => abs,
    "sin"  => sin,
    "cos"  => cos,
)

# Split an operator list into (binary_ops, unary_ops). Assumes the list
# has already passed `pysr_operators_check` so every entry is known.
function split_pysr_operators(ops_strs)
    binary = Function[]
    unary  = Function[]
    for op in ops_strs
        s = String(op)
        if haskey(PYSR_BINARY_OPS, s)
            push!(binary, PYSR_BINARY_OPS[s])
        elseif haskey(PYSR_UNARY_OPS, s)
            push!(unary, PYSR_UNARY_OPS[s])
        end
    end
    return binary, unary
end

# Default operator list when the client omits the field. Covers most
# physics/materials laws.
const DEFAULT_PYSR_OPS = ["+", "-", "*", "/", "^", "exp", "log"]

# Coerce a single JSON sample row into a Vector{Float64}. Returns
# nothing on type error so the caller can produce a bad_request.
function coerce_pysr_row(row)
    if !(row isa AbstractArray)
        return nothing
    end
    out = Vector{Float64}(undef, length(row))
    for (i, v) in enumerate(row)
        if v isa Number
            out[i] = Float64(v)
        else
            return nothing
        end
    end
    return out
end

# ─────────────────────────────────────────────────────────────────────
# Auto-normalization for /pysr (Lever 1, phase-24 §24.6)
# ─────────────────────────────────────────────────────────────────────
#
# When the client sets `autoNormalize: true`, we rescale X and y to
# unit-ish magnitudes before handing them to SymbolicRegression.jl, so
# the constant optimizer isn't fighting float64 precision on
# astronomical inputs (the canonical case: newton-gravity at SI
# magnitudes, where the discoverable coefficient is ~1e-27 and BFGS's
# gradient signal is at the float64 noise floor).
#
# Round-trip:
#   1. s_x[j] = max(median(|X[j,:]|), EPSILON_SCALE_FLOOR);
#      s_y   = max(median(|y|),       EPSILON_SCALE_FLOOR)
#   2. X' = X ./ s_x, y' = y ./ s_y
#   3. SR runs on (X', y')
#   4. For each Pareto entry: substitute var_j → var_j * (1/s_x[j]),
#      then wrap root in `* s_y`, then simplify + constant-fold so the
#      LLM-visible expression collapses the scale factors into the
#      model's own constants where possible.
#   5. Re-evaluate mse on the *original unscaled* data so the returned
#      mse number is meaningful to the caller.
#
# Cost: median is O(N log N) per column on data already in hand; tree
# rewrite is O(tree size) per Pareto entry. Both are negligible
# relative to the actual SR search.
#
# Note: the rewrite requires `*` to construct the substitution and
# root-wrap nodes. If the caller's operator set omits `*`, we inject
# it transparently — `autoNormalize` is opt-in, so the implicit
# capability addition is documented behaviour, not a surprise.

const EPSILON_SCALE_FLOOR = 1e-12

"""
    compute_robust_scales(X, y) -> (s_x, s_y)

Per-variable and target scales for auto-normalization. Uses median of
absolute values (robust to a few outliers and to signed data) with a
1e-12 floor so a zero-variance column doesn't produce a div-by-zero
in the rescale step.
"""
function compute_robust_scales(X::AbstractMatrix{T}, y::AbstractVector{T}) where T<:AbstractFloat
    nvars, _ = size(X)
    s_x = Vector{Float64}(undef, nvars)
    @inbounds for j in 1:nvars
        s_x[j] = max(median(abs.(@view X[j, :])), EPSILON_SCALE_FLOOR)
    end
    s_y = max(median(abs.(y)), EPSILON_SCALE_FLOOR)
    return s_x, s_y
end

"""
    find_or_append_mul!(binary_ops, ops_strs) -> Int

Locate the `*` callable in `binary_ops`. If absent, append it (to
both the callable list and the string-form list so the safety
allowlist stays consistent) and return the new index.
"""
function find_or_append_mul!(binary_ops::Vector{Function}, ops_strs::Vector)
    for (i, op) in enumerate(binary_ops)
        if op === (*)
            return i
        end
    end
    push!(binary_ops, (*))
    push!(ops_strs, "*")
    return length(binary_ops)
end

"""
    substitute_variables(tree, s_x, mul_op_idx) -> new tree

Non-mutating recursive rebuild: each variable leaf `x_j` becomes
`x_j * (1 / s_x[j])`. Numeric constants and internal operator nodes
pass through unchanged. Used to translate an expression learned on
rescaled inputs back to a form that evaluates correctly on the
original unscaled inputs.
"""
function substitute_variables(tree::Node{T}, s_x::Vector{Float64}, mul_op_idx::Int)::Node{T} where T
    if tree.degree == 0
        if tree.constant
            return Node{T}(; val=tree.val)
        else
            j = tree.feature
            inv_node = Node{T}(; val=T(1.0 / s_x[j]))
            var_node = Node{T}(; feature=j)
            return Node{T}(mul_op_idx, var_node, inv_node)
        end
    elseif tree.degree == 1
        return Node{T}(tree.op, substitute_variables(tree.l, s_x, mul_op_idx))
    else
        return Node{T}(tree.op,
                       substitute_variables(tree.l, s_x, mul_op_idx),
                       substitute_variables(tree.r, s_x, mul_op_idx))
    end
end

"""
    wrap_root_with_y_scale(tree, s_y, mul_op_idx) -> Node

Wrap the root in a `* s_y` node so `y/s_y → y` after the unscaling
round-trip. No-op when s_y is very close to 1 (e.g. when the target
was already in a natural range).
"""
function wrap_root_with_y_scale(tree::Node{T}, s_y::Float64, mul_op_idx::Int)::Node{T} where T
    if abs(s_y - 1.0) < 1e-12
        return tree
    end
    s_y_node = Node{T}(; val=T(s_y))
    return Node{T}(mul_op_idx, tree, s_y_node)
end

"""
    fold_constants(tree, binary_ops, unary_ops) -> Node

Bottom-up constant folding: any subtree whose evaluation depends on
no variables collapses to a single constant. Catches the `s_y * c`
and `(1/s_x[j]) * c` cases introduced by the rewrite. Domain errors
(log of a negative, sqrt of a negative) leave the offending subtree
untouched.
"""
function fold_constants(tree::Node{T}, binary_ops::Vector{Function}, unary_ops::Vector{Function})::Node{T} where T
    if tree.degree == 0
        return tree
    elseif tree.degree == 1
        folded_l = fold_constants(tree.l, binary_ops, unary_ops)
        if folded_l.degree == 0 && folded_l.constant
            f = unary_ops[tree.op]
            try
                v = T(f(folded_l.val))
                if isfinite(v)
                    return Node{T}(; val=v)
                end
            catch
            end
        end
        return Node{T}(tree.op, folded_l)
    else
        folded_l = fold_constants(tree.l, binary_ops, unary_ops)
        folded_r = fold_constants(tree.r, binary_ops, unary_ops)
        if folded_l.degree == 0 && folded_l.constant &&
           folded_r.degree == 0 && folded_r.constant
            f = binary_ops[tree.op]
            try
                v = T(f(folded_l.val, folded_r.val))
                if isfinite(v)
                    return Node{T}(; val=v)
                end
            catch
            end
        end
        # Identity simplifications: catches the (* 1.0), (+ 0.0) shapes
        # the auto-normalize rewrite introduces. Keyed on the actual
        # callable rather than the op index so caller's operator
        # ordering doesn't matter.
        op_fn = binary_ops[tree.op]
        l_is_const = folded_l.degree == 0 && folded_l.constant
        r_is_const = folded_r.degree == 0 && folded_r.constant
        if op_fn === (*)
            r_is_const && folded_r.val == one(T) && return folded_l
            l_is_const && folded_l.val == one(T) && return folded_r
        elseif op_fn === (+)
            r_is_const && folded_r.val == zero(T) && return folded_l
            l_is_const && folded_l.val == zero(T) && return folded_r
        elseif op_fn === (-)
            r_is_const && folded_r.val == zero(T) && return folded_l
        elseif op_fn === (/)
            r_is_const && folded_r.val == one(T) && return folded_l
        end
        return Node{T}(tree.op, folded_l, folded_r)
    end
end

"""
    rewrite_for_unscaling(tree, s_x, s_y, mul_op_idx, binary_ops, unary_ops, options) -> Node

Full Lever 1 round-trip: substitute variables, wrap with y scale,
simplify + fold so scale factors collapse into the model's own
constants where the algebra allows.
"""
function rewrite_for_unscaling(tree::Node{T}, s_x::Vector{Float64}, s_y::Float64,
                                mul_op_idx::Int, binary_ops::Vector{Function},
                                unary_ops::Vector{Function}, options)::Node{T} where T
    substituted = substitute_variables(tree, s_x, mul_op_idx)
    wrapped = wrap_root_with_y_scale(substituted, s_y, mul_op_idx)
    try
        SymbolicRegression.simplify_tree!(wrapped, options)
        wrapped = SymbolicRegression.combine_operators(wrapped, options)
    catch
        # SR simplification helpers can throw on unusual tree shapes;
        # fold_constants alone keeps the result correct, just slightly
        # uglier.
    end
    return fold_constants(wrapped, binary_ops, unary_ops)
end

"""
    eval_mse_on(tree, X, y, options) -> Float64

Evaluate the (rewritten, original-scale) tree on the unscaled data
and return mean-squared error. Used to refresh the `mse` field on
each Pareto entry so the caller sees error in caller-relevant units
rather than rescaled-space error from the search.
"""
function eval_mse_on(tree::Node{T}, X::AbstractMatrix{T}, y::AbstractVector{T}, options)::Float64 where T
    pred, ok = SymbolicRegression.eval_tree_array(tree, X, options)
    if !ok
        return Inf
    end
    n = length(y)
    sse = 0.0
    @inbounds for i in 1:n
        d = Float64(pred[i]) - Float64(y[i])
        sse += d * d
    end
    return sse / n
end

# ─────────────────────────────────────────────────────────────────────
# Lever 3 helpers (phase-24 §24.6): one-pass orchestration + stuck-fit
# detection + Pareto-front merge for the autoRetry path.
# ─────────────────────────────────────────────────────────────────────

"""
    run_pysr_pass(X, y; auto_normalize, ...) -> (front, evaluated)

One symbolic-regression pass over (X, y). When `auto_normalize` is
true, rescales inputs/target via Lever 1 then rewrites returned
Pareto trees back to the original (unscaled) space. Always returns a
front sorted by complexity ascending. Caller's `binary_ops` and
`ops_strs` are not mutated — local copies are made when `*` injection
is needed.
"""
function run_pysr_pass(X::AbstractMatrix{Float64}, y::AbstractVector{Float64};
                       auto_normalize::Bool,
                       ops_strs::AbstractVector,
                       binary_ops::Vector{Function},
                       unary_ops::Vector{Function},
                       var_names_str::Vector{String},
                       populations::Integer,
                       iterations::Integer,
                       max_complexity::Integer,
                       seed::Integer)::Tuple{Vector{Dict{String,Any}}, Int}
    nvars = size(X, 1)

    local_binary_ops = auto_normalize ? copy(binary_ops) : binary_ops
    local_ops_strs   = auto_normalize ? String[String(o) for o in ops_strs] : ops_strs
    mul_op_idx = 0
    if auto_normalize
        mul_op_idx = find_or_append_mul!(local_binary_ops, local_ops_strs)
    end

    s_x, s_y = auto_normalize ?
        compute_robust_scales(X, y) :
        (ones(Float64, nvars), 1.0)
    X_search = auto_normalize ? X ./ s_x : X
    y_search = auto_normalize ? y ./ s_y : y

    options = SymbolicRegression.Options(;
        binary_operators = local_binary_ops,
        unary_operators  = unary_ops,
        populations      = populations,
        maxsize          = max_complexity,
        seed             = seed,
        verbosity        = 0,
    )
    hof = SymbolicRegression.equation_search(
        X_search, y_search;
        niterations = iterations,
        options     = options,
        variable_names = var_names_str,
        parallelism = :serial,
    )
    members = SymbolicRegression.calculate_pareto_frontier(hof)
    front = Vector{Dict{String,Any}}()
    for m in members
        # `m.tree` is an Expression wrapping a Node; unwrap to a bare
        # Node{T} so our rewrite helpers (which pattern-match on Node)
        # can consume it. string_tree / eval_tree_array / compute_complexity
        # all accept Node directly with the variable_names kwarg.
        raw_node = SymbolicRegression.get_tree(m.tree)
        tree_for_caller = auto_normalize ?
            rewrite_for_unscaling(raw_node, s_x, s_y, mul_op_idx,
                                   local_binary_ops, unary_ops, options) :
            raw_node
        mse_for_caller = auto_normalize ?
            eval_mse_on(tree_for_caller, X, y, options) :
            Float64(m.loss)
        expr_str = SymbolicRegression.string_tree(
            tree_for_caller, options; variable_names=var_names_str)
        push!(front, Dict(
            "expr"       => expr_str,
            "mse"        => mse_for_caller,
            "complexity" => Int(SymbolicRegression.compute_complexity(tree_for_caller, options)),
        ))
    end
    sort!(front, by = e -> e["complexity"])
    return front, length(members)
end

"""
    fit_looks_stuck(front, y; nmse_threshold=1e-3) -> Bool

Heuristic: trip when the best Pareto entry's normalized MSE
(mse / mean(y²)) exceeds the threshold. A "perfect" fit has
NMSE ~ ε² ≈ 1e-32, so anything above 1e-3 is unambiguously a
bad fit. Empty fronts and `Inf` mse always trip.
"""
function fit_looks_stuck(front::Vector{Dict{String,Any}}, y::AbstractVector{Float64};
                         nmse_threshold::Float64=1e-3)::Bool
    isempty(front) && return true
    best_mse = minimum(Float64(get(e, "mse", Inf)) for e in front)
    if !isfinite(best_mse)
        return true
    end
    y_var = mean(y .^ 2)
    if y_var <= 0
        return false
    end
    return (best_mse / y_var) > nmse_threshold
end

"""
    merge_pareto_fronts(front_a, front_b) -> merged

Concatenate two fronts and dedup by the printed `expr` string,
keeping the entry with the smallest `mse` within each duplicate
group. Result is sorted by complexity ascending. Used to combine
the first pass and the autoRetry pass without returning two
parallel fronts to the caller.
"""
function merge_pareto_fronts(front_a::Vector{Dict{String,Any}},
                              front_b::Vector{Dict{String,Any}})::Vector{Dict{String,Any}}
    by_expr = Dict{String, Dict{String,Any}}()
    for entry in Iterators.flatten((front_a, front_b))
        key = String(get(entry, "expr", ""))
        existing = get(by_expr, key, nothing)
        if existing === nothing
            by_expr[key] = entry
        else
            mse_existing = Float64(get(existing, "mse", Inf))
            mse_new      = Float64(get(entry,    "mse", Inf))
            if mse_new < mse_existing
                by_expr[key] = entry
            end
        end
    end
    merged = collect(values(by_expr))
    sort!(merged, by = e -> Int(get(e, "complexity", 0)))
    return merged
end

function handle_pysr(req::HTTP.Request)
    t0 = time_ns()

    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    # --- 1. Required fields + shape checks ---------------------------
    samples_raw  = get(body, :samples,    nothing)
    var_names    = get(body, :varNames,   nothing)
    target_name  = get(body, :targetName, "y")
    if samples_raw === nothing || !(samples_raw isa AbstractArray) || isempty(samples_raw)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required field 'samples' (non-empty array of rows)",
            elapsed_ms)
    end
    if var_names === nothing || !(var_names isa AbstractArray) || isempty(var_names)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required field 'varNames' (non-empty array of strings)",
            elapsed_ms)
    end
    if length(samples_raw) > MAX_PYSR_SAMPLES
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'samples' exceeds cap of $(MAX_PYSR_SAMPLES) rows " *
            "(downsample on the client side)",
            elapsed_ms)
    end
    if length(var_names) > MAX_PYSR_VARS
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'varNames' exceeds cap of $(MAX_PYSR_VARS) variables",
            elapsed_ms)
    end

    # Variable names must be plain identifiers — they get baked into
    # the printed expressions.
    var_names_str = String[]
    for v in var_names
        s = String(v)
        if !occursin(r"^[A-Za-z_][A-Za-z0-9_]*$", s)
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("pysr", elapsed_ms, :error)
            return error_response("bad_request",
                "Variable name '$s' must match [A-Za-z_][A-Za-z0-9_]*",
                elapsed_ms)
        end
        push!(var_names_str, s)
    end

    nvars = length(var_names_str)
    expected_cols = nvars + 1   # vars … target

    # --- 2. Coerce samples to a P×N matrix (P=vars, N=rows) ----------
    # SymbolicRegression.jl wants `X::AbstractMatrix` where each column
    # is a sample; we receive row-per-sample JSON so we transpose.
    n_rows = length(samples_raw)
    X = Matrix{Float64}(undef, nvars, n_rows)
    y = Vector{Float64}(undef, n_rows)
    for (i, row) in enumerate(samples_raw)
        coerced = coerce_pysr_row(row)
        if coerced === nothing || length(coerced) != expected_cols
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("pysr", elapsed_ms, :error)
            return error_response("bad_request",
                "samples[$(i-1)] must be a numeric row of length $(expected_cols) " *
                "(= length(varNames) + 1 for target)",
                elapsed_ms)
        end
        for j in 1:nvars
            X[j, i] = coerced[j]
        end
        y[i] = coerced[end]
    end

    # --- 3. Operators (allowlist + default) --------------------------
    ops_strs = get(body, :operators, DEFAULT_PYSR_OPS)
    if !(ops_strs isa AbstractArray) || isempty(ops_strs)
        ops_strs = DEFAULT_PYSR_OPS
    end
    reject = pysr_operators_reject_response(ops_strs)
    if reject !== nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("pysr", elapsed_ms, :safety_reject)
        return reject
    end
    binary_ops, unary_ops = split_pysr_operators(ops_strs)

    # --- 3.5. Lever 1 + Lever 3 flag parsing (phase-24 §24.6) --------
    # Both default false for back-compat. autoNormalize rescales
    # inputs/target around the search (Lever 1); autoRetry re-runs
    # with autoNormalize forced on if the first pass produces a
    # stuck-looking fit (Lever 3 lazy trigger).
    auto_normalize = get(body, :autoNormalize, false) === true
    auto_retry     = get(body, :autoRetry,     false) === true

    # --- 4. Search parameters ---------------------------------------
    populations = get(body, :populations,       DEFAULT_PYSR_POPULATIONS)
    iterations  = get(body, :iterations,        DEFAULT_PYSR_ITERATIONS)
    max_complexity = get(body, :complexityCeiling, DEFAULT_PYSR_MAX_COMPLEXITY)
    timeout_ms_raw = get(body, :timeoutMs,      DEFAULT_PYSR_TIMEOUT_MS)
    timeout_ms = if timeout_ms_raw isa Integer && timeout_ms_raw > 0
        min(timeout_ms_raw, MAX_PYSR_TIMEOUT_MS)
    else
        DEFAULT_PYSR_TIMEOUT_MS
    end

    # Deterministic default seed (hash of samples + var names) so unit
    # tests are repeatable without the caller supplying one.
    seed = get(body, :seed, hash((samples_raw, var_names_str)) % typemax(UInt32))

    # --- 5. Acquire the per-process lock (search is not reentrant) ---
    # Try-lock with a budget so a stuck call can't starve the second.
    if !trylock(PYSR_LOCK)
        # Wait up to MAX_PYSR_QUEUE_MS for the current call to finish.
        deadline = time_ns() + MAX_PYSR_QUEUE_MS * 1_000_000
        got = false
        while time_ns() < deadline
            sleep(0.05)
            if trylock(PYSR_LOCK)
                got = true
                break
            end
        end
        if !got
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("pysr", elapsed_ms, :error)
            return error_response("busy",
                "Another /pysr call is in progress; tried for $(MAX_PYSR_QUEUE_MS)ms",
                elapsed_ms, status=503)
        end
    end

    # --- 6. Run the search(es) inside timed_evaluate, releasing the
    # lock via try/finally so even a timeout/error path clears it.
    # First pass uses the caller's autoNormalize as-is. If autoRetry
    # is on, the first pass wasn't already auto-normalized, and the
    # best fit looks stuck, we run a second pass with autoNormalize=true
    # and merge the two Pareto fronts.
    outcome = :error
    value = "uninitialised"
    try
        outcome, value = timed_evaluate(timeout_ms) do
            front_1, eval_1 = run_pysr_pass(X, y;
                auto_normalize = auto_normalize,
                ops_strs       = ops_strs,
                binary_ops     = binary_ops,
                unary_ops      = unary_ops,
                var_names_str  = var_names_str,
                populations    = populations,
                iterations     = iterations,
                max_complexity = max_complexity,
                seed           = seed,
            )
            retried = false
            merged = front_1
            evaluated_total = eval_1
            if auto_retry && !auto_normalize && fit_looks_stuck(front_1, y)
                front_2, eval_2 = run_pysr_pass(X, y;
                    auto_normalize = true,
                    ops_strs       = ops_strs,
                    binary_ops     = binary_ops,
                    unary_ops      = unary_ops,
                    var_names_str  = var_names_str,
                    populations    = populations,
                    iterations     = iterations,
                    max_complexity = max_complexity,
                    seed           = seed,
                )
                merged = merge_pareto_fronts(front_1, front_2)
                evaluated_total = eval_1 + eval_2
                retried = true
            end
            (pareto = merged, evaluated = evaluated_total, retried = retried)
        end
    finally
        unlock(PYSR_LOCK)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("pysr", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("pysr", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("pysr", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "pareto"    => value.pareto,
            "evaluated" => value.evaluated,
            "retried"   => value.retried,
        ))
    end
end

# ---------------------------------------------------------------------
# /prove_identity — SymPy WZ / Gosper machinery via PyCall (phase-25 §25.1)
# ---------------------------------------------------------------------
#
# Body: `{"algorithm": "gosper"|"zeilberger_wz"|"sister_celine"|"auto",
#         "summand": "<expr in var + free symbols>",
#         "var": "<identifier>",
#         "lower": "<int or expr>",
#         "upper": "<int or expr>",
#         "target": "<optional expected closed form>",
#         "timeoutMs": optional}`
#
# Response on success (proved):
#   {"ok": true, "status": "proved", "result": "2**n",
#    "certificate": "<antidifference or summation chain>",
#    "algorithm_used": "<gosper|zeilberger_wz|sister_celine>",
#    "elapsed_ms": N}
#
# Response on no-closed-form: status="no_closed_form", result/certificate=null.
# Errors: bad_request (400) for shape/safety, eval_error (500) for SymPy
# blow-ups, timeout (408) for elapsed_ms > timeout.
#
# The handler bridges to a process-wide SymPy instance imported at
# first-call time and memoized in SYMPY_REF. PyCall isn't reentrant
# across threads sharing one interpreter, so we serialise concurrent
# /prove_identity calls with PROVE_IDENTITY_LOCK (same shape as
# PYSR_LOCK). This is conservative — SymPy + GIL would handle some
# concurrency — but the safe default is one in-flight prove call.
#
# Safety: caller-supplied strings (summand, lower, upper) pass through
# `safety_check_sympy_expr` before sympify, which rejects dunders,
# os/subprocess references, eval/exec/import/compile/open, and
# backticks; length-capped at SYMPY_PROVE_MAX_EXPR_LEN. var must match
# the same identifier regex used by /pysr.

const SYMPY_PROVE_MAX_EXPR_LEN = 512
const SYMPY_PROVE_DEFAULT_TIMEOUT_MS = 10_000
const SYMPY_PROVE_MAX_TIMEOUT_MS     = 30_000

const PROVE_IDENTITY_LOCK = ReentrantLock()

# Forbidden substrings in caller-supplied summand / bounds. SymPy's
# sympify doesn't eval Python — but layering a denylist defends
# against future SymPy bugs or accidental parser changes that might
# permit arbitrary callable lookup.
const SYMPY_FORBIDDEN = ["__", "os.", "subprocess.", "sys.",
                         "open(", "eval(", "exec(", "compile(",
                         "import ", "lambda", "`"]

"""
    safety_check_sympy_expr(s) -> Union{Nothing, String}

Return nothing if the string is acceptable for sympify, else a
human-readable reason. Length-capped at SYMPY_PROVE_MAX_EXPR_LEN.
"""
function safety_check_sympy_expr(s::AbstractString)::Union{Nothing,String}
    length(s) > SYMPY_PROVE_MAX_EXPR_LEN &&
        return "exceeds $(SYMPY_PROVE_MAX_EXPR_LEN)-char cap"
    for pat in SYMPY_FORBIDDEN
        occursin(pat, s) && return "contains forbidden pattern '$pat'"
    end
    return nothing
end

# Lazy SymPy import. PyCall's pyimport is cached internally too but
# we keep our own ref so the first /prove_identity call after sidecar
# start pays the import cost predictably (vs spread across multiple
# pyimport calls).
const SYMPY_REF        = Ref{Any}(nothing)
const SYMPY_GOSPER_REF = Ref{Any}(nothing)
const SYMPY_TUPLE_REF  = Ref{Any}(nothing)
const SYMPY_STR_REF    = Ref{Any}(nothing)

function get_sympy()
    if SYMPY_REF[] === nothing
        SYMPY_REF[]        = pyimport("sympy")
        SYMPY_GOSPER_REF[] = pyimport("sympy.concrete.gosper")
        SYMPY_TUPLE_REF[]  = pybuiltin("tuple")
        SYMPY_STR_REF[]    = pybuiltin("str")
    end
    return SYMPY_REF[]
end

# Stringify a PyObject as SymPy would (e.g. "k*(k - 1)/2"), without
# the Julia `string()` prefix `"PyObject ..."` that wraps the repr.
function pystr(x)::String
    return String(SYMPY_STR_REF[](x))
end

"""
    try_gosper(F, k, lo, hi) -> Union{Nothing, Tuple{String,String}}

Attempt Gosper's algorithm: find indefinite antidifference T(k) such
that T(k+1) − T(k) = F(k), then the definite sum on [lo, hi] is
T(hi+1) − T(lo). Returns (closed_form_string, certificate_string)
on success, nothing if Gosper can't find an antidifference (the
sum is non-Gosper-summable) or if the certificate fails verification.
"""
function try_gosper(F, k, lo, hi)
    sympy  = SYMPY_REF[]
    gosper = SYMPY_GOSPER_REF[]
    T = gosper.gosper_sum(F, k)
    T === nothing && return nothing
    # Definite sum = T(hi+1) − T(lo).
    closed = sympy.simplify(T.subs(k, hi + 1) - T.subs(k, lo))
    # Verify the antidifference: T(k+1) − T(k) − F(k) should simplify to 0.
    verif = sympy.simplify(T.subs(k, k + 1) - T - F)
    if !pyeq(verif, sympy.S.Zero)
        # Certificate didn't algebraically verify — bail rather than
        # report a proof we can't substantiate.
        return nothing
    end
    return (pystr(closed), pystr(T))
end

# `==` semantics across PyCall: we want symbolic equality, not Python
# object identity. SymPy's `S.Zero == sympy_expr` returns a sympy bool
# we coerce to Julia Bool.
function pyeq(a, b)::Bool
    try
        return convert(Bool, a == b)
    catch
        return false
    end
end

"""
    try_wz_summation(F, k, lo, hi) -> Union{Nothing, Tuple{String,String}}

SymPy's `sympy.summation(F, (k, lo, hi))` dispatches internally to
hypergeometric machinery (the WZ family) for multi-variable definite
sums. Returns (closed_form, certificate) on success, nothing if a
residual `Sum(...)` or `Piecewise(...)` survives simplification.

The certificate here isn't the formal WZ rational R(n,k) — SymPy
doesn't surface it directly — so we record the reduction chain. The
caller's panel will see this string and can treat it as an audit
note. A future slice can compute R(n,k) ourselves on top of
SymPy primitives if the audit requires a stronger artifact.
"""
function try_wz_summation(F, k, lo, hi)
    sympy = SYMPY_REF[]
    tup   = SYMPY_TUPLE_REF[]
    s = sympy.summation(F, tup((k, lo, hi)))
    simplified = sympy.simplify(s)

    # Piecewise unwrap: SymPy is formally careful and may return
    # `Piecewise((<closed form>, <natural-bound condition>), (Sum(...), True))`.
    # If the bounds are (0, n) the user implicitly means n >= 0, so the
    # closed-form branch is the answer; the unevaluated-Sum branch is
    # the conservative fallback. Pick the first branch whose expression
    # doesn't itself contain a residual unevaluated sum.
    branch_expr = simplified
    if pyeq(simplified.func, sympy.Piecewise)
        for arg in simplified.args
            # arg is ExprCondPair(expr, cond). We want the first expr
            # that doesn't reference Sum/Integral.
            expr_part = arg[1]
            expr_str  = pystr(expr_part)
            if !occursin("Sum(", expr_str) && !occursin("Integral(", expr_str)
                branch_expr = expr_part
                break
            end
        end
    end

    sstr = pystr(branch_expr)
    # Reject results that are still unevaluated-sum sugar: SymPy's
    # `harmonic`, `polygamma`, `zeta` (when arg is symbolic), etc.,
    # are notation for the sum itself, not a closed form. A future
    # slice can whitelist specific closed forms involving these.
    UNEVALUATED_FNS = ("Sum(", "Integral(", "Piecewise(", "harmonic(",
                       "polygamma(", "RootSum(")
    if any(occursin(fn, sstr) for fn in UNEVALUATED_FNS)
        return nothing
    end
    return (sstr, "sympy.simplify(sympy.summation($(pystr(F)), ($(pystr(k)), $(pystr(lo)), $(pystr(hi)))))")
end

function handle_prove_identity(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("prove_identity", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    # Required-field parsing with explicit type narrowing — JSON3 gives
    # us SubString views into the parsed buffer; convert to String for
    # downstream PyCall + occursin safety.
    summand = String(get(body, :summand, ""))
    var     = String(get(body, :var, ""))
    lower   = String(get(body, :lower, ""))
    upper   = String(get(body, :upper, ""))
    algorithm = String(get(body, :algorithm, "auto"))

    if isempty(summand) || isempty(var) || isempty(lower) || isempty(upper)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("prove_identity", elapsed_ms, :error)
        return error_response("bad_request",
            "Missing required field (summand, var, lower, upper all required)",
            elapsed_ms)
    end

    if !(algorithm in ("gosper", "zeilberger_wz", "sister_celine", "auto"))
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("prove_identity", elapsed_ms, :error)
        return error_response("bad_request",
            "algorithm must be one of: gosper, zeilberger_wz, sister_celine, auto",
            elapsed_ms)
    end

    if !occursin(r"^[A-Za-z_][A-Za-z0-9_]*$", var)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("prove_identity", elapsed_ms, :error)
        return error_response("bad_request",
            "var '$var' must match [A-Za-z_][A-Za-z0-9_]*", elapsed_ms)
    end

    # Per-field safety check (caller-supplied expressions only).
    for (name, val) in (("summand", summand), ("lower", lower), ("upper", upper))
        rej = safety_check_sympy_expr(val)
        if rej !== nothing
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("prove_identity", elapsed_ms, :safety_reject)
            return error_response("safety_reject",
                "$(name): $(rej)", elapsed_ms, status=400)
        end
    end

    # Timeout: prove_identity uses its own ceiling rather than the
    # shared one; SymPy proofs are quick but cap at 30s.
    timeout_ms_raw = get(body, :timeoutMs, SYMPY_PROVE_DEFAULT_TIMEOUT_MS)
    timeout_ms = if timeout_ms_raw isa Integer && timeout_ms_raw > 0
        min(timeout_ms_raw, SYMPY_PROVE_MAX_TIMEOUT_MS)
    else
        SYMPY_PROVE_DEFAULT_TIMEOUT_MS
    end

    # Acquire the per-process lock. PyCall + GIL means we serialise
    # concurrent SymPy calls anyway; making the lock explicit here
    # gives a deterministic 503 if a parallel caller arrives during
    # a slow proof.
    if !trylock(PROVE_IDENTITY_LOCK)
        deadline = time_ns() + 5_000 * 1_000_000  # 5s queue
        got = false
        while time_ns() < deadline
            sleep(0.05)
            if trylock(PROVE_IDENTITY_LOCK)
                got = true
                break
            end
        end
        if !got
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("prove_identity", elapsed_ms, :error)
            return error_response("busy",
                "Another /prove_identity call is in progress; tried for 5000ms",
                elapsed_ms, status=503)
        end
    end

    # Note: NOT using `timed_evaluate` here. PyCall acquires Python's
    # GIL on the thread that called `pyimport`; `timed_evaluate` uses
    # `Threads.@spawn`, which can land on a different thread that
    # doesn't hold the GIL, producing a hard libpython crash at the
    # `PyImport_ImportModule` C entry. Until we wire a Python-side
    # `signal.alarm` timeout, we rely on (a) the 512-char summand cap,
    # (b) the safety denylist, and (c) the per-call lock to bound
    # blast radius. timeout_ms is currently advisory and only
    # surfaces in logs; cap-bounded inputs in practice resolve in
    # <1s for SymPy's WZ + Gosper machinery on simple identities.
    # TODO: install signal.alarm via PyCall for true wall-clock cap.
    _ = timeout_ms  # silence unused warning until we wire a real timeout

    outcome = :ok
    value::Any = "uninitialised"
    try
        try
            sympy = get_sympy()
            k_sym = sympy.Symbol(var, integer=true)
            # Pass `locals={var => k_sym}` so sympify uses *our* k symbol
            # (with integer=true) rather than minting a fresh assumption-
            # less Symbol(var). Without this, gosper_sum treats the
            # summand as a constant w.r.t. our k and returns nonsense.
            sym_locals = Dict(var => k_sym)
            F  = sympy.sympify(summand, sym_locals)
            lo = sympy.sympify(lower,   sym_locals)
            hi = sympy.sympify(upper,   sym_locals)

            result_pair, used_alg = if algorithm == "gosper"
                r = try_gosper(F, k_sym, lo, hi)
                (r, "gosper")
            elseif algorithm == "zeilberger_wz" || algorithm == "sister_celine"
                # Sister Celine and the WZ recurrence both route through
                # sympy.summation in v1; they diverge only in how the
                # certificate is described.
                r = try_wz_summation(F, k_sym, lo, hi)
                (r, algorithm)
            else  # auto
                r = try_gosper(F, k_sym, lo, hi)
                if r !== nothing
                    (r, "gosper")
                else
                    r2 = try_wz_summation(F, k_sym, lo, hi)
                    (r2, "zeilberger_wz")
                end
            end

            value = if result_pair === nothing
                Dict{String,Any}(
                    "status"         => "no_closed_form",
                    "result"         => nothing,
                    "certificate"    => nothing,
                    "algorithm_used" => used_alg,
                )
            else
                closed, cert = result_pair
                Dict{String,Any}(
                    "status"         => "proved",
                    "result"         => closed,
                    "certificate"    => cert,
                    "algorithm_used" => used_alg,
                )
            end
        catch e
            outcome = :error
            value = "sympy raised: " * sprint(showerror, e)
        end
    finally
        unlock(PROVE_IDENTITY_LOCK)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :error
        record_call!("prove_identity", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("prove_identity", elapsed_ms, :ok)
        return ok_response(elapsed_ms, value::Dict{String,Any})
    end
end

# ---------------------------------------------------------------------
# /numbertheory/window — port of MathConjectureSubstrate (phase-28 §28.1)
# ---------------------------------------------------------------------
#
# Body: `{"start": N, "size": N, "timeoutMs": optional}`
# Response on success:
#   {"ok": true,
#    "result": [
#      ["abundant_density",   <float>],
#      ["deficient_density",  <float>],
#      ["prime_density",      <float>],
#      ["happy_density",      <float>],
#      ["mean_omega",         <float>],
#      ["mean_Omega",         <float>],
#      ["mean_divisor_count", <float>],
#      ["max_divisor_count",  <float>],
#      ["mertens_delta",      <float>]],
#    "start": N, "size": N, "elapsed_ms": N}
#
# `result` is an ordered list of `[key, value]` pairs rather than a JSON
# object — needed because we promise byte-identical ordering with the
# JVM `LinkedHashMap` for the parity test in §28.2. JSON3 emits the
# `Vector{Pair{String,Float64}}` we hand it as a 2-element array per
# entry, which Java's Jackson re-reads in order.
#
# Errors:
#   - `bad_request` (400) — missing/invalid `start` or `size`, or window
#                            exceeds `NT_MAX_N`.
#   - `eval_error`  (500) — unexpected exception during evaluation.
#   - `timeout`     (408) — exceeded `timeoutMs`.
#
# No safety filter — input is two integers; nothing to eval.

function handle_numbertheory_window(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("numbertheory_window", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    start_v = get(body, :start, nothing)
    size_v  = get(body, :size, nothing)

    if !(start_v isa Integer) || start_v < 0
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("numbertheory_window", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'start' must be a non-negative integer", elapsed_ms)
    end
    if !(size_v isa Integer) || size_v <= 0
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("numbertheory_window", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'size' must be a positive integer", elapsed_ms)
    end
    # Fail-fast on out-of-range windows. The Julia-side check in
    # nt_evaluate_window would also raise, but we'd rather return a
    # clean 400 than a 500 wrapped around an ArgumentError.
    if Int(start_v) + Int(size_v) > NT_MAX_N + 1
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("numbertheory_window", elapsed_ms, :error)
        return error_response("bad_request",
            "Window [$(start_v), $(start_v + size_v)) exceeds NT_MAX_N=$(NT_MAX_N). " *
            "Reduce 'start + size' or raise NT_MAX_N (see phase-28).", elapsed_ms)
    end

    timeout_ms = clamp_timeout_ms(body)

    outcome, value = timed_evaluate(timeout_ms) do
        nt_evaluate_window(Int(start_v), Int(size_v))
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if outcome === :timeout
        record_call!("numbertheory_window", elapsed_ms, :timeout)
        return error_response("timeout", value::String, elapsed_ms, status=408)
    elseif outcome === :error
        record_call!("numbertheory_window", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    else  # :ok
        record_call!("numbertheory_window", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "result" => value,
            "start"  => Int(start_v),
            "size"   => Int(size_v),
        ))
    end
end

# ---------------------------------------------------------------------
# /recognise/sequence — OEIS Superseeker lookup (phase-26 §26.1)
# ---------------------------------------------------------------------
#
# Body: `{"sequence": [n1, n2, ...], "timeoutMs": optional}`
# Response on a hit:
#   {"ok": true, "recognized": true, "oeis_id": "A000045",
#    "name": "Fibonacci numbers: F(n) = ...",
#    "transform_chain": "identity", "elapsed_ms": N}
# Response on no match (still status 200):
#   {"ok": true, "recognized": false, "elapsed_ms": N}
#
# The endpoint calls oeis.org/search via HTTP.jl and parses the JSON
# response. The JVM caller is expected to maintain a Postgres cache;
# the sidecar talks to OEIS only on cache miss. OEIS asks for
# ≤1 req/sec — we don't enforce that on the sidecar side because
# the JVM cache absorbs all repeated lookups; bare-host call volume
# is tiny in practice.
#
# Errors:
#   - bad_request (400) — non-array sequence, sequence too short
#     (<4 elements; Superseeker needs enough terms to disambiguate),
#     non-integer elements, or sequence length > MAX_OEIS_TERMS.
#   - transport (502) — couldn't reach oeis.org (network, timeout,
#     rate-limit). Surfaces as `ok=false` so the caller can degrade
#     to "naked decimal" rather than escalate.
#   - eval_error (500) — unexpected exception during parse.

const OEIS_BASE_URL = "https://oeis.org/search"
const OEIS_USER_AGENT = "EmergentTerrain/26.1 (julia-math-sidecar)"
const MAX_OEIS_TERMS = 32
const MIN_OEIS_TERMS = 4   # Superseeker needs ≥4 for meaningful disambiguation
const OEIS_DEFAULT_TIMEOUT_MS = 5_000
const OEIS_MAX_TIMEOUT_MS     = 15_000

"""
    classify_transform(query_terms, oeis_data_field) -> String

Compare the queried sequence against the OEIS result's `data` field.
If the query appears as a contiguous subsequence ANYWHERE in the
canonical data — at offset 0 or shifted by k terms — this is an
"identity" match (different OEIS sequences typically use different
offset conventions; F(0)=0 vs F(1)=1 is the canonical example).
Otherwise Superseeker matched via a transform (binomial, partial
sums, etc.) — return `"transformed"` since /search doesn't expose
the specific chain in v1. A future slice can call /transforms.
"""
function classify_transform(query_terms::Vector{<:Integer},
                            oeis_data::AbstractString)::String
    # data is "0,1,1,2,3,5,8,13,..." — split on commas, parse to BigInt.
    canonical = BigInt[]
    for token in split(oeis_data, ",")
        v = tryparse(BigInt, strip(String(token)))
        v === nothing && break  # malformed; stop parsing further terms
        push!(canonical, v)
    end
    nq = length(query_terms)
    nc = length(canonical)
    if nc < nq
        return "transformed"
    end
    # Slide the query window over canonical and look for an exact match.
    @inbounds for start in 1:(nc - nq + 1)
        matched = true
        for i in 1:nq
            if canonical[start + i - 1] != BigInt(query_terms[i])
                matched = false
                break
            end
        end
        matched && return "identity"
    end
    return "transformed"
end

function handle_recognise_sequence(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_sequence", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    seq_raw = get(body, :sequence, nothing)
    if !(seq_raw isa AbstractArray) || isempty(seq_raw)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_sequence", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'sequence' must be a non-empty array of integers",
            elapsed_ms)
    end
    if length(seq_raw) < MIN_OEIS_TERMS
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_sequence", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'sequence' must contain at least $(MIN_OEIS_TERMS) terms " *
            "(Superseeker needs ≥$(MIN_OEIS_TERMS) for meaningful disambiguation)",
            elapsed_ms)
    end
    if length(seq_raw) > MAX_OEIS_TERMS
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_sequence", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'sequence' exceeds $(MAX_OEIS_TERMS) terms (cap)", elapsed_ms)
    end

    terms = Vector{Int}(undef, length(seq_raw))
    @inbounds for (i, v) in enumerate(seq_raw)
        if !(v isa Integer)
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("recognise_sequence", elapsed_ms, :error)
            return error_response("bad_request",
                "sequence[$(i-1)] must be an integer; got $(v)",
                elapsed_ms)
        end
        terms[i] = Int(v)
    end

    timeout_ms_raw = get(body, :timeoutMs, OEIS_DEFAULT_TIMEOUT_MS)
    timeout_ms = if timeout_ms_raw isa Integer && timeout_ms_raw > 0
        min(timeout_ms_raw, OEIS_MAX_TIMEOUT_MS)
    else
        OEIS_DEFAULT_TIMEOUT_MS
    end

    query_part = join(string.(terms), ",")
    url = "$(OEIS_BASE_URL)?q=signed:$(query_part)&fmt=json&start=0"

    matches_json = try
        resp = HTTP.get(url;
            headers = ["User-Agent" => OEIS_USER_AGENT],
            readtimeout = div(timeout_ms, 1000),
            connect_timeout = 5,
        )
        if resp.status != 200
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("recognise_sequence", elapsed_ms, :error)
            # OEIS returns 429 under rate limit; we treat any non-200 as
            # transport so the caller degrades to "no match" gracefully.
            return error_response("transport",
                "OEIS /search returned status $(resp.status)", elapsed_ms,
                status=502)
        end
        JSON3.read(String(resp.body))
    catch e
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_sequence", elapsed_ms, :error)
        return error_response("transport",
            "OEIS /search unreachable: $(sprint(showerror, e))",
            elapsed_ms, status=502)
    end

    # Empty response from /search means no match. Returned shape:
    # either an array of hits, or an object containing {results: [...]}
    # depending on the API mode. The `signed:` query returns an array
    # directly when matches exist, or an empty/null body when none.
    matches = if matches_json isa AbstractArray
        matches_json
    else
        []
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)

    if isempty(matches)
        record_call!("recognise_sequence", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict(
            "recognized" => false,
        ))
    end

    # First match by relevance (OEIS sorts by relevance by default).
    top = matches[1]
    a_number  = get(top, :number, nothing)
    name      = String(get(top, :name, ""))
    data_str  = String(get(top, :data, ""))

    if !(a_number isa Integer)
        record_call!("recognise_sequence", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict("recognized" => false))
    end

    transform = classify_transform(terms, data_str)
    oeis_id = "A" * lpad(string(Int(a_number)), 6, '0')

    record_call!("recognise_sequence", elapsed_ms, :ok)
    return ok_response(elapsed_ms, Dict(
        "recognized"      => true,
        "oeis_id"         => oeis_id,
        "name"            => name,
        "transform_chain" => transform,
    ))
end

# ---------------------------------------------------------------------
# /recognise/constant — PSLQ integer-relation search (phase-26 §26.1)
# ---------------------------------------------------------------------
#
# Body: `{"value": "<decimal string>", "precisionDigits": 200,
#         "basis": "BASIC"|"PHYSICS"|"COMBINATORIAL",
#         "timeoutMs": optional}`
# Response on a hit:
#   {"ok": true, "recognized": true,
#    "name": "ζ(3)",
#    "relation": [[a0, "<target>"], [a1, "1"], [a2, "π"], ...],
#    "basis_used": "BASIC", "precision_used": 200, "elapsed_ms": N}
# Response on no match:
#   {"ok": true, "recognized": false, "basis_used": "BASIC",
#    "precision_used": 200, "elapsed_ms": N}
#
# Backed by PyCall→mpmath.pslq at the requested precision. The named
# basis sets cover the constants the v1 caller is most likely to
# rediscover (physics ζ-functions + transcendentals; combinatorics
# constants are a deferred basis). A successful relation
# `[a0, a1, ..., an]` means `a0*value + a1*basis[1] + ... + an*basis[n] ≈ 0`;
# the response renders this as a clean expression when the relation is
# small (|coefficient| ≤ MAX_PSLQ_COEFF), else returns recognized=false
# even when pslq found something (noisy relations aren't useful).
#
# Safety: caller-supplied `value` string is validated as a numeric
# literal via regex before being handed to mpmath; the parser is
# never reached with arbitrary Python. The basis names are picked
# from a closed allowlist; the caller can NOT specify a custom basis.

const PSLQ_DEFAULT_PRECISION = 200
const PSLQ_MIN_PRECISION     = 30   # below this PSLQ can't disambiguate
const PSLQ_MAX_PRECISION     = 500  # above this mpmath gets slow
const PSLQ_MAX_VALUE_LEN     = 1024
const MAX_PSLQ_COEFF         = 100  # reject "noisy" multi-coefficient relations

# Allowlist of mpmath expressions per named basis. Kept as `(name,
# mpmath-expr-string)` so the response can render the relation with
# human-readable names. The mpmath-expr strings are evaluated through
# a small switch in `build_basis_value` — never via Python eval, so
# this list IS the universe of recognisable elementary constants.
const PSLQ_BASES = Dict{String, Vector{Tuple{String,String}}}(
    "BASIC" => [
        ("1",       "mpf(1)"),
        ("pi",      "pi"),
        ("pi^2",    "pi**2"),
        ("e",       "e"),
        ("ln(2)",   "ln(2)"),
        ("ln(3)",   "ln(3)"),
        ("gamma",   "euler"),
        ("zeta(3)", "apery"),
    ],
    "PHYSICS" => [
        # BASIC entries duplicated explicitly — PSLQ needs the literal
        # basis indices stable across the list.
        ("1",          "mpf(1)"),
        ("pi",         "pi"),
        ("pi^2",       "pi**2"),
        ("e",          "e"),
        ("ln(2)",      "ln(2)"),
        ("ln(3)",      "ln(3)"),
        ("gamma",      "euler"),
        ("zeta(3)",    "apery"),
        ("zeta(2)",    "zeta(2)"),
        ("zeta(5)",    "zeta(5)"),
        ("catalan",    "catalan"),
        ("phi",        "phi"),
    ],
)

const PSLQ_VALUE_PATTERN = r"^-?\d+(\.\d+)?([eE][+-]?\d+)?$"

"""
    estimate_input_precision(value_str) -> Int

Count the significant digits the caller actually supplied in
`value_str`. PSLQ's tolerance must match the input's noise floor —
a 200-digit working precision against a 30-digit input value
produces relations the tolerance check rejects because the residual
sits at ~1e-30 while tol is 1e-100. Returns the number of digits
after stripping sign, leading int-zeros, and the exponent.
"""
function estimate_input_precision(value_str::AbstractString)::Int
    m = match(r"^-?(\d+)(?:\.(\d+))?(?:[eE][+-]?\d+)?$", value_str)
    m === nothing && return 15  # fallback to double-precision-ish
    int_part = m.captures[1] === nothing ? "" : String(m.captures[1])
    frac_part = m.captures[2] === nothing ? "" : String(m.captures[2])
    # Strip leading zeros from the int part — they're not significant.
    int_stripped = lstrip(int_part, '0')
    return length(int_stripped) + length(frac_part)
end

function build_basis_value(mp, expr::AbstractString)
    # Hand-coded dispatch over the allowlisted mpmath expressions —
    # we never let the caller's basis string reach Python's eval.
    if expr == "mpf(1)"; return mp.mpf(1)
    elseif expr == "pi"; return mp.pi
    elseif expr == "pi**2"; return mp.pi^2
    elseif expr == "e"; return mp.e
    elseif expr == "ln(2)"; return mp.ln(2)
    elseif expr == "ln(3)"; return mp.ln(3)
    elseif expr == "euler"; return mp.euler
    elseif expr == "apery"; return mp.apery
    elseif expr == "zeta(2)"; return mp.zeta(2)
    elseif expr == "zeta(5)"; return mp.zeta(5)
    elseif expr == "catalan"; return mp.catalan
    elseif expr == "phi"; return mp.phi
    else
        error("Unsupported basis expression: $expr (programmer error — " *
              "an entry in PSLQ_BASES has no matching dispatch arm)")
    end
end

"""
    render_pslq_relation(relation, basis_names) -> String

Build a human-readable expression for the recognised value from a
PSLQ integer relation `[a0, a1, ..., an]` against basis names. The
relation means `a0*x + sum(ai*basis[i]) ≈ 0`, so `x = -sum/a0`.

Single-term, a0=±1, a_i=±1 cases produce the cleanest output:
"π", "-ζ(3)", "1/π" (when a0=1, a_i=π_idx with coef 1). Multi-term
relations render as the full integer linear combination. Returns a
short string suitable for direct citation by the narration layer.
"""
function render_pslq_relation(relation::Vector{Int}, basis_names::Vector{String})::String
    a0 = relation[1]
    nonzero = [(i, c) for (i, c) in enumerate(relation[2:end]) if c != 0]
    if isempty(nonzero)
        return "0"  # degenerate; caller should treat as no_match
    end
    # Build numerator string: -sum(a_i * name_i)
    parts = String[]
    for (i, c) in nonzero
        name = basis_names[i]
        neg_c = -c  # because x = -sum/a0
        sign = neg_c < 0 ? "-" : (isempty(parts) ? "" : "+")
        mag = abs(neg_c)
        term = if mag == 1
            "$(sign)$(name)"
        else
            "$(sign)$(mag)*$(name)"
        end
        push!(parts, term)
    end
    numerator = join(parts, " ")
    # Trim a leading "+ " noise
    numerator = lstrip(numerator, ['+', ' '])
    if abs(a0) == 1
        return a0 == 1 ? numerator : "-($(numerator))"
    end
    return "($(numerator)) / $(a0)"
end

function handle_recognise_constant(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_constant", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    value_str = String(get(body, :value, ""))
    if isempty(value_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_constant", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'value' is required (decimal-string form)", elapsed_ms)
    end
    if length(value_str) > PSLQ_MAX_VALUE_LEN
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_constant", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'value' exceeds $(PSLQ_MAX_VALUE_LEN)-char cap", elapsed_ms)
    end
    if !occursin(PSLQ_VALUE_PATTERN, value_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_constant", elapsed_ms, :safety_reject)
        return error_response("safety_reject",
            "Field 'value' must be a plain decimal string " *
            "(matching $(PSLQ_VALUE_PATTERN))", elapsed_ms)
    end

    basis_name = String(get(body, :basis, "BASIC"))
    if !haskey(PSLQ_BASES, basis_name)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_constant", elapsed_ms, :error)
        return error_response("bad_request",
            "Unknown basis '$(basis_name)'; must be one of " *
            join(sort(collect(keys(PSLQ_BASES))), ", "),
            elapsed_ms)
    end

    precision_raw = get(body, :precisionDigits, PSLQ_DEFAULT_PRECISION)
    precision = if precision_raw isa Integer && precision_raw > 0
        clamp(Int(precision_raw), PSLQ_MIN_PRECISION, PSLQ_MAX_PRECISION)
    else
        PSLQ_DEFAULT_PRECISION
    end

    # PyCall + Threads.@spawn don't compose (same caveat as /prove_identity);
    # run synchronously on the request thread.
    outcome = :ok
    value::Any = "uninitialised"
    try
        mpmath = pyimport("mpmath")
        mpmath.mp.dps = precision

        # Build basis: [target, b1, b2, ..., bn]
        target = mpmath.mpf(value_str)
        basis_specs = PSLQ_BASES[basis_name]
        basis_values = [build_basis_value(mpmath, spec) for (_, spec) in basis_specs]
        basis_names  = String[name for (name, _) in basis_specs]
        pslq_input = vcat([target], basis_values)

        # Tolerance must match the noise floor of the *input value*,
        # not the working precision. A 200-digit working environment
        # against a 30-digit input has residuals at ~1e-30 and a tol
        # of 1e-100 would reject every legitimate relation. Take the
        # tighter of (working precision / 2) and (input precision - 5)
        # so heavily-truncated inputs still recognise.
        input_precision = estimate_input_precision(value_str)
        tol_exp = min(div(precision, 2), max(input_precision - 5, 10))
        tol = mpmath.mpf(10)^(-tol_exp)
        # maxcoeff bounds the search; relations beyond this aren't
        # actionable for recognition.
        rel_py = mpmath.pslq(pslq_input, tol=tol, maxcoeff=MAX_PSLQ_COEFF * 1000)

        if rel_py === nothing
            value = Dict{String,Any}(
                "recognized"     => false,
                "basis_used"     => basis_name,
                "precision_used" => precision,
            )
        else
            # Coerce to Vector{Int} — mpmath returns a Python list of
            # ints. PyCall converts to Vector{Int64}.
            relation = convert(Vector{Int}, rel_py)
            # Sanity: filter out noisy relations where any coefficient
            # exceeds the friendliness bound.
            if any(abs(c) > MAX_PSLQ_COEFF for c in relation)
                value = Dict{String,Any}(
                    "recognized"     => false,
                    "basis_used"     => basis_name,
                    "precision_used" => precision,
                    "_pslq_returned" => "relation rejected as noisy " *
                                       "(max |coeff| > $(MAX_PSLQ_COEFF))",
                )
            else
                rendered = render_pslq_relation(relation, basis_names)
                # Build the relation-as-pairs for the audit field.
                pairs = Vector{Vector{Any}}()
                push!(pairs, Any[relation[1], "<target>"])
                for (i, name) in enumerate(basis_names)
                    push!(pairs, Any[relation[i+1], name])
                end
                value = Dict{String,Any}(
                    "recognized"     => true,
                    "name"           => rendered,
                    "relation"       => pairs,
                    "basis_used"     => basis_name,
                    "precision_used" => precision,
                )
            end
        end
    catch e
        outcome = :error
        value = "mpmath/pslq threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("recognise_constant", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("recognise_constant", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

