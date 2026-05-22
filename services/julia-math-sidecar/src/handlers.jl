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

# ---------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------

# Default per-call timeout in milliseconds. Phase-22 §22.5 calls for a
# `app.julia.maxEvalMs` knob on the EmergentTerrain side; the sidecar
# enforces its own ceiling on top, so a buggy client can't ask for a
# 60-second hang.
const DEFAULT_TIMEOUT_MS = 5_000
const MAX_TIMEOUT_MS     = 30_000   # absolute ceiling

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

    # --- 4. Search parameters ---------------------------------------
    populations = get(body, :populations,       DEFAULT_PYSR_POPULATIONS)
    iterations  = get(body, :iterations,        DEFAULT_PYSR_ITERATIONS)
    max_complexity = get(body, :complexityCeiling, DEFAULT_PYSR_MAX_COMPLEXITY)
    timeout_ms_raw = get(body, :timeoutMs,      DEFAULT_PYSR_TIMEOUT_MS)
    timeout_ms = if timeout_ms_raw isa Integer && timeout_ms_raw > 0
        min(timeout_ms_raw, MAX_TIMEOUT_MS)
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

    # --- 6. Run the search inside timed_evaluate, releasing the lock
    # via try/finally so even a timeout/error path clears it.
    outcome = :error
    value = "uninitialised"
    try
        outcome, value = timed_evaluate(timeout_ms) do
            options = SymbolicRegression.Options(;
                binary_operators = binary_ops,
                unary_operators  = unary_ops,
                populations      = populations,
                maxsize          = max_complexity,
                seed             = seed,
                verbosity        = 0,
            )
            hof = SymbolicRegression.equation_search(
                X, y;
                niterations = iterations,
                options     = options,
                variable_names = var_names_str,
                parallelism = :serial,
            )
            # Convert the hall-of-fame to a Pareto front sorted by
            # complexity ascending. Each entry: (expr_string, mse, complexity).
            #
            # `string_tree` is the canonical printer in SymbolicRegression.jl;
            # it honours variable_names so the LLM sees `x * y` rather than
            # the internal `x0 * x1` positional form. Cost of the rename
            # is one pass over the tree; trivial relative to the search.
            members = SymbolicRegression.calculate_pareto_frontier(hof)
            front = Vector{Dict{String,Any}}()
            for m in members
                expr_str = SymbolicRegression.string_tree(
                    m.tree, options; variable_names=var_names_str)
                push!(front, Dict(
                    "expr"       => expr_str,
                    "mse"        => Float64(m.loss),
                    "complexity" => Int(SymbolicRegression.compute_complexity(m.tree, options)),
                ))
            end
            sort!(front, by = e -> e["complexity"])
            (pareto = front, evaluated = length(members))
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
        ))
    end
end
