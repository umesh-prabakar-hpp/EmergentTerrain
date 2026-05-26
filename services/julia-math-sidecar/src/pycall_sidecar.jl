# =====================================================================
# pycall_sidecar.jl — PyCall (sympy/mpmath) endpoints, ISOLATED MODULE
# =====================================================================
#
# Deliberately NOT included by the JuliaMathSidecar package (see
# src/JuliaMathSidecar.jl). Loaded at RUNTIME, only by the py-sidecar
# process (scripts/start-py-sidecar.sh), via:
#
#     using JuliaMathSidecar
#     include(joinpath(pkgdir(JuliaMathSidecar), "src", "pycall_sidecar.jl"))
#     PyCallSidecar.start_py_server!(host=..., port=...)
#
# WHY ISOLATED (the segfault fix)
# -------------------------------
# `using PyCall` initialises libpython in the host process. PyCall's
# `pydecref` finalizer must run on the GIL-holding thread, but Julia's GC
# runs finalizers on whatever thread triggers a collection. In the main
# server (JULIA_NUM_THREADS>1) a finalizer fired on a SymbolicRegression
# worker thread during /pysr → off-GIL `_Py_Dealloc` → SIGSEGV (observed
# twice in logs/sidecar.log, both inside optimize_and_simplify_population).
# Removing PyCall from warmup was not enough: the module-level `using
# PyCall` in handlers.jl loaded libpython regardless.
#
# Keeping `using PyCall` OUT of the package load chain means the main
# 4-thread server never initialises libpython. PyCall lives only here, in
# a process pinned to ONE thread (start-py-sidecar.sh forces
# JULIA_NUM_THREADS=1) where every finalizer necessarily runs on the GIL
# thread — safe. The main server proxies these routes here when started
# with PY_SIDECAR_ENABLED=true.
# =====================================================================

module PyCallSidecar

using HTTP
using Logging
using PyCall

# Shared, PyCall-free helpers live in the main package; pull them in by
# name (works for non-exported names). None of these touch Python.
using JuliaMathSidecar: safe_parse_body, ok_response, error_response,
                        record_call!, init_metrics!,
                        handle_healthz, handle_metrics

# ─── moved from handlers.jl: /prove_identity (phase-25 §25.1) ───────────
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

# ─── moved from handlers.jl: /recognise/{constant,minpoly} (phase-26) ───
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

# ---------------------------------------------------------------------
# /recognise/minpoly — minimal polynomial recovery (phase-26 §26.3)
# ---------------------------------------------------------------------
#
# Body: `{"value": "<decimal>", "degreeBound": 4,
#         "precisionDigits": 200, "timeoutMs": optional}`
# Response on a hit:
#   {"ok": true, "recognized": true,
#    "minPoly": "x^4 - 10*x^2 + 1",
#    "degree": 4,
#    "coefficients": [1, 0, -10, 0, 1],   // ascending: a0..ad
#    "precision_used": 200,
#    "elapsed_ms": N}
# Response on no relation:
#   {"ok": true, "recognized": false, "precision_used": 200, "elapsed_ms": N}
#
# Approach: PSLQ on the polynomial basis [1, α, α², ..., α^d]
# recovers the integer relation a0 + a1·α + a2·α² + ... + ad·α^d ≈ 0.
# That IS the minimal polynomial (or an integer multiple). The
# historical Phase 26 doc called this the "LLL slice" and proposed
# wrapping Nemo.jl's FLINT-backed LLL; for v1 we observe that PSLQ
# on the polynomial basis is mathematically equivalent for this
# specific problem (single-row lattice reduction) and reuses the
# already-imported mpmath/PyCall infrastructure with zero new
# Julia-side dependencies. If a future workload demands the
# higher-dimensional LLL features (cryptanalysis-size lattices),
# the slice can be re-engineered against Nemo.jl without changing
# the HTTP contract.
#
# Safety: same value-string validation as /recognise/constant.
# degreeBound clamped to [MIN_LLL_DEGREE, MAX_LLL_DEGREE].
# maxcoeff is wider than PSLQ-on-constants because minimal-polynomial
# coefficients can legitimately reach into the thousands (e.g.,
# Chebyshev-like algebraic numbers).

const MIN_LLL_DEGREE   = 2     # degree 1 is trivially α = -a0/a1
const MAX_LLL_DEGREE   = 12    # higher than this and PSLQ noise dominates
const DEFAULT_LLL_DEGREE = 4   # matches the §26.3 acceptance bar (√2+√3)
const MAX_LLL_COEFF    = 10_000

"""
    render_polynomial(coefficients) -> String

Build a clean polynomial string from an ascending coefficient
array `[a0, a1, ..., ad]`. Examples:
  [1, 0, -10, 0, 1] → "x^4 - 10*x^2 + 1"
  [-1, 0, 1]        → "x^2 - 1"
  [0, 0, 1]         → "x^2"
  [5]               → "5"
"""
function render_polynomial(coefficients::Vector{Int})::String
    n = length(coefficients)
    parts = String[]
    # Walk degrees high-to-low for natural reading order.
    for i in n:-1:1
        a = coefficients[i]
        a == 0 && continue
        degree = i - 1
        mag = abs(a)
        # Variable part: "x^degree" / "x" / "" (for the constant term)
        var_part = if degree == 0
            ""
        elseif degree == 1
            "x"
        else
            "x^$(degree)"
        end
        # Coefficient part: "n*" prefix for mag != 1 (or for the constant
        # term, just the magnitude); for mag == 1 and degree > 0, omit
        # the coefficient entirely.
        coeff_part = if degree == 0
            string(mag)
        elseif mag == 1
            ""
        else
            "$(mag)*"
        end
        term = coeff_part * var_part
        sign = if isempty(parts)
            a < 0 ? "-" : ""
        else
            a < 0 ? " - " : " + "
        end
        push!(parts, sign * term)
    end
    return isempty(parts) ? "0" : join(parts)
end

function handle_recognise_minpoly(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_minpoly", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end

    value_str = String(get(body, :value, ""))
    if isempty(value_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_minpoly", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'value' is required (decimal-string form)", elapsed_ms)
    end
    if length(value_str) > PSLQ_MAX_VALUE_LEN
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_minpoly", elapsed_ms, :error)
        return error_response("bad_request",
            "Field 'value' exceeds $(PSLQ_MAX_VALUE_LEN)-char cap", elapsed_ms)
    end
    if !occursin(PSLQ_VALUE_PATTERN, value_str)
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("recognise_minpoly", elapsed_ms, :safety_reject)
        return error_response("safety_reject",
            "Field 'value' must be a plain decimal string " *
            "(matching $(PSLQ_VALUE_PATTERN))", elapsed_ms)
    end

    degree_raw = get(body, :degreeBound, DEFAULT_LLL_DEGREE)
    degree = if degree_raw isa Integer && degree_raw > 0
        clamp(Int(degree_raw), MIN_LLL_DEGREE, MAX_LLL_DEGREE)
    else
        DEFAULT_LLL_DEGREE
    end

    precision_raw = get(body, :precisionDigits, PSLQ_DEFAULT_PRECISION)
    precision = if precision_raw isa Integer && precision_raw > 0
        clamp(Int(precision_raw), PSLQ_MIN_PRECISION, PSLQ_MAX_PRECISION)
    else
        PSLQ_DEFAULT_PRECISION
    end

    outcome = :ok
    value::Any = "uninitialised"
    try
        mpmath = pyimport("mpmath")
        mpmath.mp.dps = precision

        alpha = mpmath.mpf(value_str)
        # Build [1, α, α², ..., α^degree]
        basis = Vector{Any}(undef, degree + 1)
        basis[1] = mpmath.mpf(1)
        basis[2] = alpha
        for k in 3:(degree + 1)
            basis[k] = basis[k-1] * alpha
        end

        input_precision = estimate_input_precision(value_str)
        tol_exp = min(div(precision, 2), max(input_precision - 5, 10))
        tol = mpmath.mpf(10)^(-tol_exp)

        rel_py = mpmath.pslq(basis, tol=tol, maxcoeff=MAX_LLL_COEFF)

        if rel_py === nothing
            value = Dict{String,Any}(
                "recognized"     => false,
                "precision_used" => precision,
                "degree_bound"   => degree,
            )
        else
            coefficients = convert(Vector{Int}, rel_py)
            # Reject "trivial" polynomials (all-zero or single-term).
            nonzero_count = count(c -> c != 0, coefficients)
            if nonzero_count < 2
                value = Dict{String,Any}(
                    "recognized"     => false,
                    "precision_used" => precision,
                    "degree_bound"   => degree,
                    "_pslq_returned" => "trivial relation ($nonzero_count nonzero coeffs)",
                )
            elseif any(abs(c) > MAX_LLL_COEFF for c in coefficients)
                value = Dict{String,Any}(
                    "recognized"     => false,
                    "precision_used" => precision,
                    "degree_bound"   => degree,
                    "_pslq_returned" => "relation rejected as noisy " *
                                       "(max |coeff| > $(MAX_LLL_COEFF))",
                )
            else
                # Compute actual degree (highest nonzero coefficient).
                actual_degree = 0
                for i in length(coefficients):-1:1
                    if coefficients[i] != 0
                        actual_degree = i - 1
                        break
                    end
                end
                # Conventional normalisation: leading coefficient
                # positive. If it's negative, negate the whole relation.
                if coefficients[actual_degree + 1] < 0
                    coefficients = [-c for c in coefficients]
                end
                minpoly_str = render_polynomial(coefficients)
                value = Dict{String,Any}(
                    "recognized"     => true,
                    "minPoly"        => minpoly_str,
                    "degree"         => actual_degree,
                    "coefficients"   => coefficients,
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
        record_call!("recognise_minpoly", elapsed_ms, :error)
        return error_response("eval_error", value::String, elapsed_ms, status=500)
    end
    record_call!("recognise_minpoly", elapsed_ms, :ok)
    return ok_response(elapsed_ms, value::Dict{String,Any})
end

# ---------------------------------------------------------------------
# Router + server loop (moved from server.jl — these reference the
# PyCall handlers above, so they belong with them, not in the main pkg)
# ---------------------------------------------------------------------

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
(4 threads) stays PyCall-free (never loads this file) and proxies here when
PY_SIDECAR_ENABLED=true.
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

end  # module PyCallSidecar
