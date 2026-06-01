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
    merge_pareto_fronts(fronts::Vector{Vector{Dict}}) -> merged

Concatenate any number of Pareto fronts and dedup by the printed
`expr` string (a cheap proxy for structural equivalence — two trees
that print identically are the same law), keeping the entry with the
smallest `mse` within each duplicate group. Result is sorted by
complexity ascending. Used by both the Lever 3 autoRetry path (two
fronts) and the Lever 2 ensemble path (K fronts).
"""
function merge_pareto_fronts(fronts::Vector{Vector{Dict{String,Any}}})::Vector{Dict{String,Any}}
    by_expr = Dict{String, Dict{String,Any}}()
    for front in fronts, entry in front
        key = String(get(entry, "expr", ""))
        existing = get(by_expr, key, nothing)
        if existing === nothing
            by_expr[key] = entry
        else
            if Float64(get(entry, "mse", Inf)) < Float64(get(existing, "mse", Inf))
                by_expr[key] = entry
            end
        end
    end
    merged = collect(values(by_expr))
    sort!(merged, by = e -> Int(get(e, "complexity", 0)))
    return merged
end

"""
    merge_pareto_fronts(front_a, front_b) -> merged

Two-front convenience for the Lever 3 autoRetry path; delegates to
the N-front merge above.
"""
merge_pareto_fronts(front_a::Vector{Dict{String,Any}},
                    front_b::Vector{Dict{String,Any}})::Vector{Dict{String,Any}} =
    merge_pareto_fronts(Vector{Dict{String,Any}}[front_a, front_b])

# ─────────────────────────────────────────────────────────────────────
# Lever 2 (phase-24 §24.6): resource-adaptive PySR ensemble
# ─────────────────────────────────────────────────────────────────────
#
# Levers 1 and 3 attack the *algorithmic* failure class (BFGS precision
# floor) by rescaling. Lever 2 attacks both classes *probabilistically*
# by running several independent search strategies in parallel and
# unioning their Pareto fronts. The intuition from §24.6: GP escape from
# a local optimum is cheaper with K decorrelated islands than with one
# island run K× longer, and different *representations* (raw vs
# normalized vs log-space) reshape the fitness landscape so a basin that
# traps one variant isn't a basin for another.
#
# Resource gating
# ---------------
# The fan-out width K is sized to available cores, read once at module
# load (`SIDECAR_CPU_THREADS`). The §24.6 tier table:
#   1–2 cores → normalized only          (K=1)
#   4         → + raw                     (K=2)
#   8         → + log-input/log-target    (K=3)
#   16+       → + operator-subset island  (K=4)
# On a 1–2 core box the ensemble collapses to a single normalized pass,
# i.e. Lever 1 behaviour — so turning the flag on is never *worse* than
# Lever 1 alone, it just can't do more without cores to spend.
#
# Concurrency / the PYSR_LOCK question
# ------------------------------------
# §24.6 framed Lever 2 as requiring "lifting PYSR_LOCK to permit K
# independent Options instances per request". The refactor that made
# this safe already landed: `run_pysr_pass` builds a *fresh* `Options`
# per call and the "parallel equation_search safety" test proves K
# disjoint Options can run concurrently without cross-contamination. So
# the ensemble fans out under a *single* hold of PYSR_LOCK (one request
# in flight at a time, K islands inside it) rather than lifting the lock
# entirely — the lock still serialises *separate* requests, which is the
# property the busy/queue path depends on. Lifting it for multi-tenant
# throughput is a separate, later concern (§24.6 "PYSR_LOCK becomes the
# throughput bottleneck").
#
# Flag-gated dormant
# ------------------
# Activated per-request by `autoEnsemble: true` (default off, like
# autoNormalize/autoRetry). The capability ships ready; flipping it on
# is an operator decision once a multi-core sidecar is in scope. Tests
# and callers may pin the core count via `ensembleCores` so behaviour is
# deterministic regardless of the host.

# Logical-core count, read once at load. `Sys.CPU_THREADS` counts
# hyperthreads; we size the fan-out off it. A request's `ensembleCores`
# overrides this for deterministic tests / pinned deployments.
const SIDECAR_CPU_THREADS = Sys.CPU_THREADS

# One ensemble member: a named search strategy over the same (X, y).
#   :normalized — Lever 1 robust-median rescale + unscaling round-trip
#   :raw        — search on the data exactly as supplied
#   :log        — log-transform inputs+target, round-trip via exp(·)∘log
#   :opsubset   — raw search restricted to the multiplicative/power core
struct PysrVariant
    name::String
    kind::Symbol
    operators::Vector{String}
end

"""
    ensemble_operator_subset(base_ops) -> ops

The operator subset for the `:opsubset` island: the arithmetic /
power core (`+ - * / ^`) with transcendental unary ops (exp, log,
sqrt, sin, cos) dropped, biasing this island toward polynomial /
rational laws over a smaller search space. Falls back to the full set
if the intersection would be empty (so the island always has
something to search).
"""
function ensemble_operator_subset(base_ops::Vector{String})::Vector{String}
    core = String[op for op in base_ops if op in ("+", "-", "*", "/", "^")]
    return isempty(core) ? copy(base_ops) : core
end

"""
    select_ensemble_variants(cores, base_ops_strs) -> Vector{PysrVariant}

Build the ensemble plan for a given core budget per the §24.6 tier
table. Always includes the `:normalized` island; wider core budgets
append `:raw`, `:log`, then `:opsubset`. Tiers round down — a 6-core
box gets the 4-core plan, a 12-core box the 8-core plan.
"""
function select_ensemble_variants(cores::Integer, base_ops_strs)::Vector{PysrVariant}
    base = String[String(o) for o in base_ops_strs]
    variants = PysrVariant[PysrVariant("normalized", :normalized, base)]
    cores >= 4  && push!(variants, PysrVariant("raw",      :raw, base))
    cores >= 8  && push!(variants, PysrVariant("log",      :log, base))
    cores >= 16 && push!(variants, PysrVariant("opsubset", :opsubset,
                                               ensemble_operator_subset(base)))
    return variants
end

"""
    _find_or_append_unary!(unary_ops, fn) -> Int

Locate `fn` in `unary_ops`, appending it if absent. Returns its
index. The log-space round-trip needs `log` and `exp` available as
unary callables to construct the substitution / wrap nodes even when
the caller's operator set omitted them.
"""
function _find_or_append_unary!(unary_ops::Vector{Function}, fn::Function)::Int
    for (i, op) in enumerate(unary_ops)
        op === fn && return i
    end
    push!(unary_ops, fn)
    return length(unary_ops)
end

"""
    substitute_variables_with_log(tree, log_op_idx) -> new tree

Non-mutating rebuild: each variable leaf `x_j` becomes `log(x_j)`.
Constants and operator nodes pass through. Translates an expression
learned on log-transformed inputs back to one that consumes the
original (unlogged) inputs.
"""
function substitute_variables_with_log(tree::Node{T}, log_op_idx::Int)::Node{T} where T
    if tree.degree == 0
        if tree.constant
            return Node{T}(; val=tree.val)
        else
            return Node{T}(log_op_idx, Node{T}(; feature=tree.feature))
        end
    elseif tree.degree == 1
        return Node{T}(tree.op, substitute_variables_with_log(tree.l, log_op_idx))
    else
        return Node{T}(tree.op,
                       substitute_variables_with_log(tree.l, log_op_idx),
                       substitute_variables_with_log(tree.r, log_op_idx))
    end
end

"""
    wrap_root_with_exp(tree, exp_op_idx) -> Node

Wrap the root in `exp(·)` so a model fit to `log(y)` predicts `y`
after the round-trip. The two halves compose to
`y ≈ exp( f( log(x_1), …, log(x_n) ) )`.
"""
function wrap_root_with_exp(tree::Node{T}, exp_op_idx::Int)::Node{T} where T
    return Node{T}(exp_op_idx, tree)
end

"""
    run_pysr_log_pass(X, y; …) -> (front, evaluated)

The `:log` ensemble island. Log-linearises multiplicative / power
laws (`y = a·x^b` ⇒ `log y = log a + b·log x`) so the GP can find them
with `+`/`*` alone. Searches on `(log X, log y)`, then rewrites each
Pareto tree back to original space via `exp(·)∘log` and re-evaluates
`mse` on the unlogged data so returned numbers are caller-meaningful.

Applicability: log needs strictly positive inputs *and* target. If any
entry is ≤ 0 the island is inapplicable and returns an empty front —
the ensemble merge just drops it, so a mixed-sign sweep degrades to the
other islands rather than erroring.
"""
function run_pysr_log_pass(X::AbstractMatrix{Float64}, y::AbstractVector{Float64};
                           ops_strs::AbstractVector,
                           binary_ops::Vector{Function},
                           unary_ops::Vector{Function},
                           var_names_str::Vector{String},
                           populations::Integer,
                           iterations::Integer,
                           max_complexity::Integer,
                           seed::Integer)::Tuple{Vector{Dict{String,Any}}, Int}
    if any(<=(0.0), X) || any(<=(0.0), y)
        return Vector{Dict{String,Any}}(), 0
    end

    X_search = log.(X)
    y_search = log.(y)

    search_options = SymbolicRegression.Options(;
        binary_operators = binary_ops,
        unary_operators  = unary_ops,
        populations      = populations,
        maxsize          = max_complexity,
        seed             = seed,
        verbosity        = 0,
    )
    hof = SymbolicRegression.equation_search(
        X_search, y_search;
        niterations = iterations,
        options     = search_options,
        variable_names = var_names_str,
        parallelism = :serial,
    )
    members = SymbolicRegression.calculate_pareto_frontier(hof)

    # Rewrite/eval options: same binary ops in the same order (so search
    # trees' op indices stay valid) plus log + exp appended as unary ops
    # so the round-trip nodes are constructible even if the search set
    # omitted them.
    rw_unary = copy(unary_ops)
    log_idx = _find_or_append_unary!(rw_unary, log)
    exp_idx = _find_or_append_unary!(rw_unary, exp)
    rewrite_options = SymbolicRegression.Options(;
        binary_operators = binary_ops,
        unary_operators  = rw_unary,
        populations      = populations,
        maxsize          = max_complexity,
        seed             = seed,
        verbosity        = 0,
    )

    front = Vector{Dict{String,Any}}()
    for m in members
        raw_node = SymbolicRegression.get_tree(m.tree)
        subbed = substitute_variables_with_log(raw_node, log_idx)
        wrapped = wrap_root_with_exp(subbed, exp_idx)
        folded = fold_constants(wrapped, binary_ops, rw_unary)
        mse = eval_mse_on(folded, X, y, rewrite_options)
        expr_str = SymbolicRegression.string_tree(
            folded, rewrite_options; variable_names=var_names_str)
        push!(front, Dict(
            "expr"       => expr_str,
            "mse"        => mse,
            "complexity" => Int(SymbolicRegression.compute_complexity(folded, rewrite_options)),
        ))
    end
    sort!(front, by = e -> e["complexity"])
    return front, length(members)
end

"""
    run_one_variant(v, X, y; …) -> (front, evaluated)

Dispatch a single ensemble member to the right pass. `:normalized`,
`:raw`, `:opsubset` route through `run_pysr_pass` (auto_normalize
on only for `:normalized`); `:log` routes through `run_pysr_log_pass`.
Each variant gets its own freshly-split operator vectors so nothing
is shared across the parallel islands.
"""
function run_one_variant(v::PysrVariant, X::AbstractMatrix{Float64},
                          y::AbstractVector{Float64};
                          var_names_str::Vector{String},
                          populations::Integer, iterations::Integer,
                          max_complexity::Integer,
                          seed::Integer)::Tuple{Vector{Dict{String,Any}}, Int}
    bin, un = split_pysr_operators(v.operators)
    if v.kind === :log
        return run_pysr_log_pass(X, y;
            ops_strs=v.operators, binary_ops=bin, unary_ops=un,
            var_names_str=var_names_str, populations=populations,
            iterations=iterations, max_complexity=max_complexity, seed=seed)
    else
        return run_pysr_pass(X, y;
            auto_normalize = (v.kind === :normalized),
            ops_strs=v.operators, binary_ops=bin, unary_ops=un,
            var_names_str=var_names_str, populations=populations,
            iterations=iterations, max_complexity=max_complexity, seed=seed)
    end
end

"""
    run_pysr_ensemble(X, y; variants, …) -> (merged_front, total_evaluated, variants_run)

Fan out the variants concurrently (one `Threads.@spawn` per island,
each behind its own `per_variant_timeout_ms` budget via
`timed_evaluate`), then union their Pareto fronts. A variant that
times out or errors contributes nothing — the ensemble degrades
gracefully to whatever islands finished. Each island gets a
seed offset so the GP populations are decorrelated rather than K
identical searches. `variants_run` names the islands that returned a
non-empty front, for the response's diagnostics.
"""
function run_pysr_ensemble(X::AbstractMatrix{Float64}, y::AbstractVector{Float64};
                           variants::Vector{PysrVariant},
                           var_names_str::Vector{String},
                           populations::Integer, iterations::Integer,
                           max_complexity::Integer, seed::Integer,
                           per_variant_timeout_ms::Int)
    tasks = Vector{Task}(undef, length(variants))
    for (i, v) in enumerate(variants)
        variant_seed = Int(seed) + i   # decorrelate islands
        tasks[i] = Threads.@spawn timed_evaluate(per_variant_timeout_ms) do
            run_one_variant(v, X, y;
                var_names_str=var_names_str, populations=populations,
                iterations=iterations, max_complexity=max_complexity,
                seed=variant_seed)
        end
    end

    all_fronts = Vector{Vector{Dict{String,Any}}}()
    total_eval = 0
    variants_run = String[]
    for (i, t) in enumerate(tasks)
        outcome, value = fetch(t)
        if outcome === :ok
            front, ev = value
            push!(all_fronts, front)
            total_eval += ev
            isempty(front) || push!(variants_run, variants[i].name)
        end
    end
    merged = merge_pareto_fronts(all_fronts)
    return merged, total_eval, variants_run
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

    # Lever 2 (phase-24 §24.6): resource-adaptive ensemble. Default off.
    # When on, supersedes the single-pass + autoRetry logic — the
    # ensemble already runs a normalized island, which is what the
    # autoRetry path falls back to. `ensembleCores` pins the fan-out
    # width for tests / fixed deployments; absent, we read the box.
    auto_ensemble = get(body, :autoEnsemble, false) === true
    ensemble_cores_raw = get(body, :ensembleCores, nothing)
    ensemble_cores = ensemble_cores_raw isa Integer && ensemble_cores_raw > 0 ?
        Int(ensemble_cores_raw) : SIDECAR_CPU_THREADS

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
            if auto_ensemble
                # Lever 2: fan out K islands sized to cores, union fronts.
                variants = select_ensemble_variants(ensemble_cores, ops_strs)
                merged, evaluated_total, variants_run = run_pysr_ensemble(X, y;
                    variants       = variants,
                    var_names_str  = var_names_str,
                    populations    = populations,
                    iterations     = iterations,
                    max_complexity = max_complexity,
                    seed           = seed,
                    per_variant_timeout_ms = timeout_ms,
                )
                return (pareto = merged, evaluated = evaluated_total,
                        retried = false, ensemble = true, variants = variants_run)
            end

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
            (pareto = merged, evaluated = evaluated_total, retried = retried,
             ensemble = false, variants = String[])
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
            "ensemble"  => value.ensemble,
            "variants"  => value.variants,
        ))
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
# /conjecture/seed + /conjecture/update (phase-28 §28.3)
# ---------------------------------------------------------------------
#
# Ports JVM ConjectureEngine.seed + ConjectureEngine.update to the
# sidecar. JSON wire shape matches the JVM Inequality + Witness
# records exactly; the §28.3 parity test pins byte-identical output
# between the JVM in-process path and these endpoints.
#
# /conjecture/seed body:  {"samples": [{"name": value, ...}, ...],
#                          "vocabulary": ["name1", "name2", ...]}
# /conjecture/seed reply: {"ok":true, "inequalities":[Inequality,...],
#                          "elapsed_ms":N}
#
# /conjecture/update body:  {"store":[Inequality,...],
#                            "runKey":"W500_low",
#                            "configHash":"W500",
#                            "samples":[{...},...]}
# /conjecture/update reply: {"ok":true, "confirmed":N, "retired":M,
#                            "retiredItems":[Inequality,...],
#                            "store":[Inequality,...],
#                            "elapsed_ms":N}
#
# These are pure numeric computations — no safety filter. The
# vocabulary array is the surface of caller-controlled strings;
# we don't eval anything, only use them as Dict keys.

"""
    _deep_plain(x)

Recursively convert JSON3.Object / JSON3.Array values to plain Julia
Dict{String,Any} / Vector{Any}. JSON3's default types use Symbol keys
on objects, which break `haskey(dict, "string-key")` checks later in
the pipeline. After this pass every dict has String keys and every
array is a plain Vector.
"""
function _deep_plain(x)
    if x isa AbstractDict
        return Dict{String,Any}(String(k) => _deep_plain(v) for (k, v) in x)
    elseif x isa AbstractArray
        return Any[_deep_plain(v) for v in x]
    else
        return x
    end
end

function _coerce_samples(samples_raw)::Union{Vector, String}
    if !(samples_raw isa AbstractArray)
        return "Field 'samples' must be an array of {name: value} objects"
    end
    out = Vector{Dict{String,Float64}}()
    for (i, s) in enumerate(samples_raw)
        if !(s isa AbstractDict)
            return "samples[$(i-1)] must be an object {name: value}"
        end
        d = Dict{String,Float64}()
        for (k, v) in s
            if v isa Number
                d[String(k)] = Float64(v)
            elseif v isa Bool
                # Skip booleans silently — they're invalid as scalar values
            else
                return "samples[$(i-1)][$(k)] must be numeric; got $(typeof(v))"
            end
        end
        push!(out, d)
    end
    return out
end

function handle_conjecture_seed(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_seed", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    samples_raw = get(body, :samples, nothing)
    vocab_raw   = get(body, :vocabulary, nothing)
    if samples_raw === nothing || vocab_raw === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_seed", elapsed_ms, :error)
        return error_response("bad_request",
            "Required fields: samples (array), vocabulary (array of names)",
            elapsed_ms)
    end
    coerced = _coerce_samples(samples_raw)
    if coerced isa String
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_seed", elapsed_ms, :error)
        return error_response("bad_request", coerced, elapsed_ms)
    end
    if !(vocab_raw isa AbstractArray)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_seed", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'vocabulary' must be an array of strings", elapsed_ms)
    end
    vocab = String[String(v) for v in vocab_raw]

    outcome = :ok
    value::Any = "uninitialised"
    try
        inequalities = conjecture_seed(coerced, vocab)
        value = Dict{String,Any}("inequalities" => inequalities)
    catch e
        outcome = :error
        value = "conjecture_seed threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("conjecture_seed", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("conjecture_seed", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

function handle_conjecture_update(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_update", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    store_raw   = get(body, :store, nothing)
    run_key     = String(get(body, :runKey, ""))
    config_hash = String(get(body, :configHash, ""))
    samples_raw = get(body, :samples, nothing)

    if store_raw === nothing || samples_raw === nothing || isempty(run_key) || isempty(config_hash)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_update", elapsed_ms, :error)
        return error_response("bad_request",
            "Required fields: store (array of Inequality), runKey (string), " *
            "configHash (string), samples (array)",
            elapsed_ms)
    end
    if !(store_raw isa AbstractArray)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_update", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'store' must be an array of Inequality objects", elapsed_ms)
    end

    # Coerce store entries to plain Dicts so conjecture_update can read
    # them with `get(d, key, default)`. _deep_plain unwraps the nested
    # JSON3 types (slackByConfig object, refutedBy array of objects)
    # to plain String-keyed Dicts and Vectors.
    store = Vector{Dict{String,Any}}()
    for (i, e) in enumerate(store_raw)
        if !(e isa AbstractDict)
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("conjecture_update", elapsed_ms, :error)
            return error_response("bad_request",
                "store[$(i-1)] must be an Inequality object",
                elapsed_ms)
        end
        d = _deep_plain(e)
        # Defaults — caller may omit fields on freshly-seeded inequalities.
        for (k, default) in (("confirmedRuns", 0), ("confirmedConfigs", String[]),
                              ("refutedBy", Dict{String,Any}[]),
                              ("p99Slack", 0.0),
                              ("slackByConfig", Dict{String,Float64}()),
                              ("droppedByKnob", nothing))
            if !haskey(d, k)
                d[k] = default
            end
        end
        push!(store, d)
    end

    coerced_samples = _coerce_samples(samples_raw)
    if coerced_samples isa String
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_update", elapsed_ms, :error)
        return error_response("bad_request", coerced_samples, elapsed_ms)
    end

    outcome = :ok
    value::Any = "uninitialised"
    try
        result = conjecture_update(store, run_key, config_hash, coerced_samples)
        value = Dict{String,Any}(
            "confirmed"    => result.confirmed,
            "retired"      => length(result.retiredItems),
            "retiredItems" => result.retiredItems,
            "store"        => result.store,
        )
    catch e
        outcome = :error
        value = "conjecture_update threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("conjecture_update", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("conjecture_update", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

# ---------------------------------------------------------------------
# /conjecture/verify — held-out verification (phase-28 §28.4)
# ---------------------------------------------------------------------
#
# Body: {lhs, rhs, k, c, windows: [{start, size}, ...]}.
# Response (ok=true):
#   {held, worstMargin, worstWindowStart, windowsEvaluated,
#    windows: [{windowStart, lhsValue, rhsValue, margin, holds}, ...]}
#
# Algorithm (mirrors ConjectureVerifier.verify byte-for-byte):
#   For each requested window:
#     1. nt_evaluate_window(start, size) → raw BASE map.
#     2. math_invariants_extract(raw, start, size) → flat map with
#        BASE + DERIVED + window metadata (_window_start, _window_size,
#        _tick). _tick = windowStart so the conjecture-engine update
#        path picks the same tick from the worst sample.
#     3. lookup lhs, rhs in the flat map (default 0.0 if absent).
#     4. margin = lhs - (k * rhs + c); holds = margin <= 2*TOL.
#     5. Track worst margin + corresponding windowStart.
#
# Caller-side validation: lhs/rhs must be in the vocabulary
# (math_invariants_vocabulary). The handler rejects out-of-vocabulary
# names with bad_request so the engine doesn't silently default to 0.0
# for a typo.

function handle_conjecture_verify(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    lhs = String(get(body, :lhs, ""))
    rhs = String(get(body, :rhs, ""))
    k_raw = get(body, :k, nothing)
    c_raw = get(body, :c, nothing)
    windows_raw = get(body, :windows, nothing)

    if isempty(lhs) || isempty(rhs) || k_raw === nothing ||
            c_raw === nothing || windows_raw === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("bad_request",
            "Required fields: lhs (string), rhs (string), k (number), " *
            "c (number), windows (array of {start, size})", elapsed_ms)
    end

    vocab = math_invariants_vocabulary()
    if !(lhs in vocab)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("bad_request",
            "Unknown lhs scalar '$(lhs)'. Vocabulary: " *
            join(vocab, ", "), elapsed_ms)
    end
    if !(rhs in vocab)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("bad_request",
            "Unknown rhs scalar '$(rhs)'. Vocabulary: " *
            join(vocab, ", "), elapsed_ms)
    end

    k = Float64(k_raw)
    c = Float64(c_raw)

    if !(windows_raw isa AbstractArray)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'windows' must be an array of {start, size} objects",
            elapsed_ms)
    end

    # Pre-parse windows so we surface bad shapes before any compute.
    parsed_windows = Vector{Tuple{Int,Int}}()
    for (i, w) in enumerate(windows_raw)
        if !(w isa AbstractDict)
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("conjecture_verify", elapsed_ms, :error)
            return error_response("bad_request",
                "windows[$(i-1)] must be an object {start, size}",
                elapsed_ms)
        end
        ws = _deep_plain(w)
        s_raw = get(ws, "start", nothing)
        sz_raw = get(ws, "size", nothing)
        if s_raw === nothing || sz_raw === nothing
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("conjecture_verify", elapsed_ms, :error)
            return error_response("bad_request",
                "windows[$(i-1)] missing 'start' or 'size'",
                elapsed_ms)
        end
        s  = Int(s_raw)
        sz = Int(sz_raw)
        if s < 0 || sz <= 0
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("conjecture_verify", elapsed_ms, :error)
            return error_response("bad_request",
                "windows[$(i-1)] requires start>=0 and size>0", elapsed_ms)
        end
        if s + sz > NT_MAX_N + 1
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("conjecture_verify", elapsed_ms, :error)
            return error_response("bad_request",
                "windows[$(i-1)] extends to $(s + sz) which exceeds " *
                "NT_MAX_N=$(NT_MAX_N) + 1", elapsed_ms)
        end
        push!(parsed_windows, (s, sz))
    end

    outcome = :ok
    value::Any = "uninitialised"
    try
        held = true
        worst_margin = -Inf
        worst_window_start = parsed_windows[1][1]
        reports = Vector{Dict{String,Any}}()
        for (s, sz) in parsed_windows
            raw_pairs = nt_evaluate_window(s, sz)
            flat_pairs = math_invariants_extract(raw_pairs, s, sz)
            # Convert to lookup dict for O(1) lhs/rhs access.
            flat = Dict{String,Float64}()
            for (name, v) in flat_pairs
                flat[name] = v
            end
            lhs_v = get(flat, lhs, 0.0)
            rhs_v = get(flat, rhs, 0.0)
            margin = lhs_v - (k * rhs_v + c)
            holds = margin <= 2.0 * CONJ_TOL
            push!(reports, Dict{String,Any}(
                "windowStart" => s,
                "lhsValue"    => lhs_v,
                "rhsValue"    => rhs_v,
                "margin"      => margin,
                "holds"       => holds,
            ))
            if margin > worst_margin
                worst_margin       = margin
                worst_window_start = s
            end
            if !holds
                held = false
            end
        end
        value = Dict{String,Any}(
            "held"             => held,
            "worstMargin"      => worst_margin,
            "worstWindowStart" => worst_window_start,
            "windowsEvaluated" => length(parsed_windows),
            "windows"          => reports,
        )
    catch e
        outcome = :error
        value = "conjecture_verify threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("conjecture_verify", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("conjecture_verify", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

# ---------------------------------------------------------------------
# /predicate/scan — DynamicComputeEngine port (phase-28 §28.5a)
# ---------------------------------------------------------------------
#
# Body: {predicate, metric, rangeStart, rangeEnd, baseline}
# Response (ok=true): numeric scalars only (count, fraction, deviation,
# mean, se, max, status). The JVM caller layers on the findings /
# hypothesis / equation prose so we avoid byte-identical-format
# friction between Java String.format and Julia @sprintf.
#
# Metrics:
#   fraction_satisfying — proportion of [rangeStart, rangeEnd) satisfying pred
#   mean_of:<fnName>    — Welford mean of fn(i) over matching i
#   max_of:<fnName>     — max of fn(i) over matching i
#
# Predicate allowlist (PREDICATE_FNS): isPrime, isHappy,
# collatzTerminates, isAbundant, isPerfect. Value-fn allowlist
# (VALUE_FNS): collatzSteps, sigmaSum.
#
# The /predicate/scan parity test in EmergentTerrain pins
# byte-identical numeric output against the in-process JVM
# DynamicComputeEngine.

function handle_predicate_scan(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("predicate_scan", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    predicate_spec = String(get(body, :predicate, ""))
    metric         = String(get(body, :metric, "fraction_satisfying"))
    range_start_raw = get(body, :rangeStart, nothing)
    range_end_raw   = get(body, :rangeEnd, nothing)
    baseline_raw    = get(body, :baseline, nothing)

    if isempty(predicate_spec) || range_start_raw === nothing ||
            range_end_raw === nothing || baseline_raw === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("predicate_scan", elapsed_ms, :error)
        return error_response("bad_request",
            "Required fields: predicate (string), rangeStart (int), " *
            "rangeEnd (int), baseline (number). " *
            "metric optional (defaults to fraction_satisfying)",
            elapsed_ms)
    end

    range_start = Int(range_start_raw)
    range_end   = Int(range_end_raw)
    baseline    = Float64(baseline_raw)

    # JVM clamp: start := max(1, rangeStart); end := min(rangeEnd, start + MAX_RANGE)
    start = max(1, range_start)
    end_  = min(range_end, start + PREDICATE_MAX_RANGE)
    n = end_ - start

    if n <= 0
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("predicate_scan", elapsed_ms, :ok)
        return ok_response(elapsed_ms, Dict{String,Any}(
            "metric" => "empty",
            "n"      => 0,
            "_note"  => "empty range after clamp; JVM returns " *
                        "DynamicComputeEngine.emptyResult-equivalent",
        ))
    end

    pred = parse_predicate(predicate_spec)
    if pred === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("predicate_scan", elapsed_ms, :error)
        return error_response("bad_request",
            "Unknown predicate '$(predicate_spec)'. Available: " *
            join(sort(collect(keys(PREDICATE_FNS))), ", ") *
            " (combined with ' AND ' or ' OR ')",
            elapsed_ms)
    end

    outcome = :ok
    value::Any = "uninitialised"
    try
        if metric == "fraction_satisfying"
            value = predicate_scan_fraction(start, end_, n, pred, baseline)
        elseif startswith(metric, "mean_of:") || startswith(metric, "max_of:")
            fn_name = metric[(findfirst(':', metric) + 1):end]
            val_fn = parse_value_fn(fn_name)
            if val_fn === nothing
                elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
                record_call!("predicate_scan", elapsed_ms, :error)
                return error_response("bad_request",
                    "Unknown value function '$(fn_name)'. Available: " *
                    join(sort(collect(keys(VALUE_FNS))), ", "),
                    elapsed_ms)
            end
            is_max = startswith(metric, "max_of:")
            value = predicate_scan_value(start, end_, pred, val_fn, baseline, is_max)
        else
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("predicate_scan", elapsed_ms, :error)
            return error_response("bad_request",
                "Unknown metric '$(metric)'. Expected " *
                "'fraction_satisfying', 'mean_of:<fnName>', or 'max_of:<fnName>'",
                elapsed_ms)
        end
    catch e
        outcome = :error
        value = "predicate_scan threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("predicate_scan", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("predicate_scan", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

# ---------------------------------------------------------------------
# /physics/topic — MathPhysicsEngine.compute port (phase-28 §28.5b)
# ---------------------------------------------------------------------
#
# Body: {domain, topicIdx, param1, param2, searchParams: {stepSize,
#   restartProb, candidateBreadth, depthBias, acceptThreshold}}.
# Response on a ported (domain, topicIdx) pair:
#   {ok: true, primaryValue, secondaryValue, tertiaryValue,
#    reviewValue, claimStatus, theoryConsistent, ...domain-specific
#    detail fields..., elapsed_ms}
# Response when the (domain, topicIdx) pair is NOT YET PORTED:
#   {ok: false, error: "not_ported", explanation: "...", elapsed_ms}
# — and the JVM JuliaMathClient.physicsTopic surfaces this as
# Optional.empty() so callers transparently fall back to in-process
# MathPhysicsEngine.compute.
#
# Wire contract (matches §28.5a /predicate/scan): numeric scalars +
# ClaimStatus name only. The JVM caller composes the final findings /
# hypothesis / equation / claimAssessment prose locally to avoid
# byte-identical-format friction.

function handle_physics_topic(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("physics_topic", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    domain    = String(get(body, :domain, ""))
    topic_raw = get(body, :topicIdx, nothing)
    p1_raw    = get(body, :param1, nothing)
    p2_raw    = get(body, :param2, nothing)
    sp_raw    = get(body, :searchParams, nothing)

    if isempty(domain) || topic_raw === nothing ||
            p1_raw === nothing || p2_raw === nothing || sp_raw === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("physics_topic", elapsed_ms, :error)
        return error_response("bad_request",
            "Required fields: domain (string), topicIdx (int), " *
            "param1 (int), param2 (number), searchParams " *
            "({stepSize, restartProb, candidateBreadth, depthBias, " *
            "acceptThreshold})",
            elapsed_ms)
    end

    topic_idx = Int(topic_raw)
    param1    = Int(p1_raw)
    param2    = Float64(p2_raw)
    sp        = _deep_plain(sp_raw)
    if !(sp isa AbstractDict) || !haskey(sp, "candidateBreadth") ||
            !haskey(sp, "stepSize")
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("physics_topic", elapsed_ms, :error)
        return error_response("bad_request",
            "searchParams must include at least 'candidateBreadth' and 'stepSize'",
            elapsed_ms)
    end

    outcome = :ok
    value::Any = "uninitialised"
    try
        result = physics_compute(domain, topic_idx, param1, param2, sp)
        if result === nothing
            elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
            record_call!("physics_topic", elapsed_ms, :error)
            # not_ported is NOT a server error — it's a routing signal.
            # Use 404 so the JVM client can distinguish it from
            # generic 4xx and surface Optional.empty().
            return error_response("not_ported",
                "(domain='$(domain)', topicIdx=$(topic_idx)) is not yet " *
                "ported to the sidecar; JVM caller should fall back to " *
                "in-process MathPhysicsEngine.compute. Tracked under " *
                "phase-28 §28.5b continuations.",
                elapsed_ms, status=404)
        end
        value = result
    catch e
        outcome = :error
        value = "physics_compute threw: " * sprint(showerror, e)
    end

    elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
    if outcome === :error
        record_call!("physics_topic", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("physics_topic", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

# =====================================================================
# /discover/validate — fork 2b Phase 2 (runtime self-verifying execution)
# =====================================================================
# Runs an LLM-authored `candidate(x)` in SandboxRunner's isolated
# subprocess and judges its output against the trusted nt_mertens_dense
# oracle on the overlap range. ENV-GATED: inert (HTTP 403) unless
# ET_DISCOVER_EXEC=1, so the route is dormant by default even though it is
# registered. Two gates total — this one plus the Java
# app.research.discovery.runtimeExecEnabled flag (won't call it otherwise).
# Scope: phase-30-conjecture/runtime-self-verifying-execution.md.
#
# Request JSON:
#   { "code": "<julia defining candidate(x)>",
#     "checkpoints": [1,2,10,...],       # 1..NT_MAX_N — judged vs nt_mertens_dense
#     "anchors": {"10000000": 1037},     # beyond-range external truth (optional)
#     "target": 100000000,               # value wanted if accepted (optional)
#     "cpuSeconds": 10, "wallSeconds": 15, "memKb": 0 }   # optional limits
# Response: ok + status, verdict (accept|reject|na), values, oracle,
#   firstFail, target, targetValue.
const DISCOVER_MAX_CHECKPOINTS = 64

function handle_discover_validate(req::HTTP.Request)
    t0 = time_ns()
    el() = round(Int, (time_ns() - t0) / 1_000_000)

    # SIDECAR-side gate — dormant by default: no parsing, no execution.
    if get(ENV, "ET_DISCOVER_EXEC", "0") != "1"
        record_call!("discover_validate", el(), :error)
        return error_response("disabled",
            "discovery execution is disabled; set ET_DISCOVER_EXEC=1 to enable", el(); status=403)
    end

    body = safe_parse_body(req)
    if body === nothing
        record_call!("discover_validate", el(), :error)
        return error_response("bad_request", "Body is not valid JSON", el())
    end

    code = get(body, :code, nothing)
    if !(code isa AbstractString) || isempty(strip(String(code)))
        record_call!("discover_validate", el(), :error)
        return error_response("bad_request", "Field 'code' must be a non-empty string", el())
    end

    cps_raw = get(body, :checkpoints, nothing)
    if !(cps_raw isa AbstractVector) || isempty(cps_raw) || !all(x -> x isa Integer, cps_raw)
        record_call!("discover_validate", el(), :error)
        return error_response("bad_request",
            "Field 'checkpoints' must be a non-empty array of integers", el())
    end
    checkpoints = Int.(collect(cps_raw))
    if length(checkpoints) > DISCOVER_MAX_CHECKPOINTS
        record_call!("discover_validate", el(), :error)
        return error_response("bad_request", "Too many checkpoints (max $(DISCOVER_MAX_CHECKPOINTS))", el())
    end
    if any(k -> k < 1 || k > NT_MAX_N, checkpoints)
        record_call!("discover_validate", el(), :error)
        return error_response("bad_request",
            "All checkpoints must be in 1..NT_MAX_N=$(NT_MAX_N) (the oracle range)", el())
    end

    anchors = Dict{Int,Int}()
    araw = get(body, :anchors, nothing)
    if araw !== nothing
        try
            for (k, v) in pairs(araw)
                anchors[parse(Int, String(k))] = Int(v)
            end
        catch
            record_call!("discover_validate", el(), :error)
            return error_response("bad_request", "Field 'anchors' must map integer→integer", el())
        end
    end

    target = get(body, :target, nothing)
    target_i = target isa Integer ? Int(target) : nothing

    # Safety backstop on the per-candidate sandbox budget. Ceilings give headroom above the
    # Java-side config (app.research.discovery.frontierCpuSeconds/WallSeconds = 60/80) so the
    # configured value passes through and is the single source of truth; this only blocks an
    # absurd request. NOTE: a candidate can monopolise the single-thread sidecar for up to
    # wall_s — raising these widens the frontier reach but also the worst-case block time
    # (see frontier-escalation-loop.md §10 deferred mitigation). Keep wall_s > cpu_s.
    cpu_s  = clamp(Int(get(body, :cpuSeconds, 10)),  1, 90)
    wall_s = clamp(Int(get(body, :wallSeconds, 15)), 1, 120)
    mem_kb = Int(get(body, :memKb, 0))

    # Trusted oracle: ONE cumulative pass to the max checkpoint over the
    # validated nt_mobius, capturing M at each checkpoint. Runs in this
    # (trusted) process — never in the sandbox.
    oracle = Dict{Int,Int}()
    let want = Set(checkpoints), maxk = maximum(checkpoints), acc = 0
        @inbounds for n in 1:maxk
            acc += nt_mobius(n)
            (n in want) && (oracle[n] = acc)
        end
    end

    inputs = collect(union(Set(checkpoints), Set(keys(anchors)),
                           target_i === nothing ? Set{Int}() : Set([target_i])))

    res = SandboxRunner.run_sandboxed(String(code), inputs;
            cpu_seconds=cpu_s, wall_seconds=wall_s, mem_kb=mem_kb)

    if res.status !== :ok
        record_call!("discover_validate", el(), :ok)   # endpoint worked; candidate failed/was rejected
        return ok_response(el(), Dict(
            "status"  => String(res.status),
            "verdict" => "na",
            "detail"  => res.detail === nothing ? "" : string(res.detail),
        ))
    end

    verdict, fail = SandboxRunner.moat_judge(res, oracle, anchors)
    extra = Dict{String,Any}(
        "status"  => "ok",
        "verdict" => String(verdict),
        "values"  => Dict(string(k) => v for (k, v) in res.values),
        "oracle"  => Dict(string(k) => v for (k, v) in oracle),
    )
    if verdict === :reject && fail !== nothing
        extra["firstFail"] = Any[fail...]
    end
    if verdict === :accept && target_i !== nothing
        extra["target"] = target_i
        extra["targetValue"] = get(res.values, target_i, nothing)
    end
    record_call!("discover_validate", el(), :ok)
    return ok_response(el(), extra)
end

