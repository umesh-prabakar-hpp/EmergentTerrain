# =====================================================================
# test/runtests.jl — sidecar smoke tests
# =====================================================================
#
# Two layers:
#   1. Unit tests on the safety filter — no server needed; pure function.
#   2. Integration tests on the HTTP surface — start the server on a
#      random port, hit each endpoint with curl-equivalent HTTP.request,
#      assert response shape.
#
# Invocation (from the sidecar repo root):
#   julia --project=. -e 'using Pkg; Pkg.test()'
#
# The second layer assumes packages are precompiled — a fresh checkout
# needs ~1-2 minutes to compile Symbolics.jl on first run.
# =====================================================================

using Test
using HTTP
using JSON3
using JuliaMathSidecar
using SymbolicRegression
using Statistics: median

# PyCall-backed endpoints (/prove_identity, /recognise/{constant,minpoly}) live
# in src/pycall_sidecar.jl — NOT loaded by the package, so the production main
# server stays PyCall-free. Load it here to exercise those handlers in-process
# (the test process is single-threaded, so PyCall finalizers are safe).
include(joinpath(@__DIR__, "..", "src", "pycall_sidecar.jl"))

# Reach into the module to test private functions. `Base.@invokelatest`
# isn't needed because we're loaded at the same world-age.
const Safety  = JuliaMathSidecar
const Metrics = JuliaMathSidecar

# ---------------------------------------------------------------------
# Safety filter — unit
# ---------------------------------------------------------------------

@testset "safety_check rejects forbidden patterns" begin
    # A representative sample — full list is in src/safety.jl.
    for bad in [
        "using Pkg",
        "Pkg.add(\"Foo\")",
        "run(`ls`)",
        "`echo hi`",
        "open(\"/etc/passwd\")",
        "ccall((:foo, :libc), …)",
        "include(\"/tmp/x.jl\")",
        "Sys.iswindows()",
        "eval(:(1+1))",
    ]
        result = JuliaMathSidecar.safety_check(bad)
        @test result isa JuliaMathSidecar.SafetyReject
    end
end

@testset "safety_check accepts ordinary math expressions" begin
    for ok in [
        "sin(π/4)",
        "floor(2.04e-6 / 400e-9)",
        "[i^2 for i in 1:5]",
        "(x^2 + 2x + 1) / (x + 1)",
        "factorial(big(20))",
    ]
        result = JuliaMathSidecar.safety_check(ok)
        @test result isa JuliaMathSidecar.SafetyOk
    end
end

@testset "pysr_operators_check allowlist (phase-24)" begin
    @test JuliaMathSidecar.pysr_operators_check(
        ["+", "-", "*", "/", "^", "exp", "log"]) isa JuliaMathSidecar.SafetyOk
    @test JuliaMathSidecar.pysr_operators_check(["sin", "cos", "sqrt", "abs"]) isa
        JuliaMathSidecar.SafetyOk
    # Out-of-allowlist operator.
    rejected = JuliaMathSidecar.pysr_operators_check(["+", "tanh"])
    @test rejected isa JuliaMathSidecar.SafetyReject
    @test rejected.pattern == "tanh"
    # Function-name that looks math-y but is shell-y.
    rejected2 = JuliaMathSidecar.pysr_operators_check(["+", "run"])
    @test rejected2 isa JuliaMathSidecar.SafetyReject
    # Non-string entry.
    rejected3 = JuliaMathSidecar.pysr_operators_check(["+", 42])
    @test rejected3 isa JuliaMathSidecar.SafetyReject
end

@testset "auto-normalize round-trip math (Lever 1, phase-24 §24.6)" begin
    # Synthetic newton-gravity data: y = c * m1 * m2 with c = 6.674e-27
    # and inputs at raw SI mass magnitudes (1e22..1e24). This is the
    # canonical fixture the Lever 1 rewrite has to handle: scales span
    # ~50 OOM between inputs and the recoverable coefficient.
    m1 = [1.0e24, 3.0e24, 5.0e24, 2.5e24, 4.5e24]
    m2 = [2.0e22, 4.0e22, 6.0e22, 3.5e22, 5.5e22]
    c_true = 6.674e-27
    y = c_true .* m1 .* m2
    X = Matrix{Float64}(undef, 2, 5)
    X[1, :] .= m1
    X[2, :] .= m2

    s_x, s_y = JuliaMathSidecar.compute_robust_scales(X, y)
    @test s_x[1] ≈ 3.0e24  rtol=1e-12
    @test s_x[2] ≈ 4.0e22  rtol=1e-12
    @test s_y ≈ median(abs.(y))  rtol=1e-12

    # Rescaled data ought to live in [0, ~10].
    X_rescaled = X ./ s_x
    y_rescaled = y ./ s_y
    @test all(0 .<= X_rescaled .<= 10)
    @test all(0 .<= y_rescaled .<= 10)

    # Build a tree that mimics what SR would converge to on rescaled
    # data: (x1 * x2) * c_rescaled. The rewrite must produce a tree
    # whose evaluation on the *original unscaled* X matches y.
    binary_ops = Function[(+), (-), (*), (/)]
    unary_ops = Function[]
    options = SymbolicRegression.Options(
        binary_operators = binary_ops,
        unary_operators  = unary_ops,
        populations = 4, maxsize = 30, seed = 42, verbosity = 0,
    )
    mul_idx = 3
    T = Float64
    c_rescaled = c_true * s_x[1] * s_x[2] / s_y
    sr_output = Node{T}(
        mul_idx,
        Node{T}(mul_idx, Node{T}(; feature=1), Node{T}(; feature=2)),
        Node{T}(; val=c_rescaled),
    )

    rewritten = JuliaMathSidecar.rewrite_for_unscaling(
        sr_output, s_x, s_y, mul_idx, binary_ops, unary_ops, options)
    pred, ok = SymbolicRegression.eval_tree_array(rewritten, X, options)
    @test ok
    # Relative error on every training row should be at float64 epsilon.
    @test all(abs.(pred .- y) ./ abs.(y) .< 1e-12)

    # Out-of-sample: the rewritten expression must still recover the
    # underlying law on points outside the median-defining sample set.
    m1_test = [7.0e24, 1.5e24]
    m2_test = [8.0e22, 0.5e22]
    y_test = c_true .* m1_test .* m2_test
    X_test = Matrix{Float64}(undef, 2, 2)
    X_test[1, :] .= m1_test
    X_test[2, :] .= m2_test
    pred_test, ok_test = SymbolicRegression.eval_tree_array(rewritten, X_test, options)
    @test ok_test
    @test all(abs.(pred_test .- y_test) ./ abs.(y_test) .< 1e-12)
end

@testset "auto-normalize fold_constants identity (Lever 1, phase-24 §24.6)" begin
    binary_ops = Function[(+), (-), (*), (/)]
    unary_ops = Function[]
    T = Float64
    # Variable * 1.0 collapses to just the variable.
    var = Node{T}(; feature=1)
    times_one = Node{T}(3, var, Node{T}(; val=1.0))
    folded = JuliaMathSidecar.fold_constants(times_one, binary_ops, unary_ops)
    @test folded.degree == 0 && !folded.constant && folded.feature == 1
    # Variable + 0.0 collapses similarly.
    plus_zero = Node{T}(1, var, Node{T}(; val=0.0))
    @test JuliaMathSidecar.fold_constants(plus_zero, binary_ops, unary_ops).feature == 1
    # (2.0 + 3.0) folds to 5.0.
    const_sum = Node{T}(1, Node{T}(; val=2.0), Node{T}(; val=3.0))
    folded_sum = JuliaMathSidecar.fold_constants(const_sum, binary_ops, unary_ops)
    @test folded_sum.degree == 0 && folded_sum.constant && folded_sum.val ≈ 5.0
end

@testset "run_pysr_pass end-to-end (Lever 1 + 3 regression, phase-24 §24.6)" begin
    # This test covers the path from equation_search → calculate_pareto_frontier
    # → get_tree(Expression) → rewrite_for_unscaling(Node). A bug in the
    # Expression-vs-Node typing shipped in ff4d40e because the synthetic
    # round-trip test bypassed equation_search; this regression catches
    # it. y = 7 * x  on a small linear fixture so SR converges fast.
    n = 12
    x_vals = collect(range(1.0, 6.0; length=n))
    y_vals = 7.0 .* x_vals
    X = reshape(x_vals, 1, n)
    front, evaluated = JuliaMathSidecar.run_pysr_pass(X, y_vals;
        auto_normalize = true,
        ops_strs       = String["+", "-", "*"],
        binary_ops     = Function[(+), (-), (*)],
        unary_ops      = Function[],
        var_names_str  = String["x"],
        populations    = 4,
        iterations     = 12,
        max_complexity = 10,
        seed           = 42,
    )
    @test evaluated > 0
    @test !isempty(front)
    best_mse = minimum(Float64(get(e, "mse", Inf)) for e in front)
    @test best_mse < 1e-10  # search recovers y = 7x to float precision

    # Same pass with auto_normalize=false should also work (covers the
    # non-rewrite path through get_tree).
    front_raw, _ = JuliaMathSidecar.run_pysr_pass(X, y_vals;
        auto_normalize = false,
        ops_strs       = String["+", "-", "*"],
        binary_ops     = Function[(+), (-), (*)],
        unary_ops      = Function[],
        var_names_str  = String["x"],
        populations    = 4,
        iterations     = 12,
        max_complexity = 10,
        seed           = 42,
    )
    @test !isempty(front_raw)
end

@testset "fit_looks_stuck (Lever 3, phase-24 §24.6)" begin
    y = Float64[1.0, 2.0, 3.0, 4.0, 5.0]
    # A clean fit (mse ~ 0) should NOT look stuck.
    good_front = [Dict{String,Any}("expr"=>"x", "mse"=>1e-20, "complexity"=>1)]
    @test !JuliaMathSidecar.fit_looks_stuck(good_front, y)
    # A bad fit (mse ~ var(y)) SHOULD look stuck.
    bad_front = [Dict{String,Any}("expr"=>"0", "mse"=>10.0, "complexity"=>1)]
    @test JuliaMathSidecar.fit_looks_stuck(bad_front, y)
    # Empty front trips trivially.
    @test JuliaMathSidecar.fit_looks_stuck(Dict{String,Any}[], y)
    # Inf mse trips.
    inf_front = [Dict{String,Any}("expr"=>"oops", "mse"=>Inf, "complexity"=>1)]
    @test JuliaMathSidecar.fit_looks_stuck(inf_front, y)
end

@testset "merge_pareto_fronts (Lever 3, phase-24 §24.6)" begin
    front_a = [
        Dict{String,Any}("expr"=>"x", "mse"=>1.0, "complexity"=>1),
        Dict{String,Any}("expr"=>"x*y", "mse"=>0.5, "complexity"=>3),
    ]
    front_b = [
        Dict{String,Any}("expr"=>"x", "mse"=>0.1, "complexity"=>1),  # better mse, same expr
        Dict{String,Any}("expr"=>"x+y", "mse"=>0.3, "complexity"=>3),
    ]
    merged = JuliaMathSidecar.merge_pareto_fronts(front_a, front_b)
    @test length(merged) == 3   # x deduped; x*y and x+y both kept
    # x entry should have the better mse (0.1 from front_b)
    x_entry = first(e for e in merged if e["expr"] == "x")
    @test x_entry["mse"] ≈ 0.1
    # Sorted by complexity ascending — `x` first, then `x*y` and `x+y` tied
    @test merged[1]["complexity"] == 1
end

# ---------------------------------------------------------------------
# Per-operator Lever 1 round-trip identity (phase-24 §24.6)
# ---------------------------------------------------------------------
#
# Property: `rewrite_for_unscaling(T, s_x, s_y, ...)` evaluated on
# unscaled X must equal `T` evaluated on (X ./ s_x) times s_y. This
# is the defining identity of Lever 1's rewrite — if it holds, the
# rewritten tree faithfully transports an SR-found expression from
# rescaled-space back to caller-space. Deterministic; no dependence
# on SR convergence.
#
# Covers every operator currently in PYSR_BINARY_OPS ∪ PYSR_UNARY_OPS.
# A new operator added to either map needs a row here too.

"""
    check_roundtrip(op_name, tree_factory, x_test, binary_ops, unary_ops)

Build the tree via tree_factory, choose synthetic scales, apply
rewrite_for_unscaling, and assert the round-trip identity holds at
float64 epsilon on every test point.
"""
function check_roundtrip(op_name::String,
                          tree_factory::Function,
                          x_test::Matrix{Float64},
                          binary_ops::Vector{Function},
                          unary_ops::Vector{Function})
    T = Float64
    options = SymbolicRegression.Options(
        binary_operators = binary_ops,
        unary_operators  = unary_ops,
        populations = 2, maxsize = 30, seed = 1, verbosity = 0,
    )
    # Find the index of `*` in binary_ops — required by the rewrite.
    mul_idx = findfirst(==(*), binary_ops)
    @assert mul_idx !== nothing "test setup error: $op_name needs `*` in binary_ops"

    tree = tree_factory()
    nvars = size(x_test, 1)

    # Synthetic scales chosen to be non-trivial in every dimension.
    s_x = [1.7, 2.3, 0.9][1:nvars]
    s_y = 4.1

    # Expected unscaled output: original tree evaluated on (X / s_x), times s_y.
    x_rescaled = x_test ./ s_x
    expected_rescaled, ok_e = SymbolicRegression.eval_tree_array(tree, x_rescaled, options)
    @assert ok_e "test setup error: $op_name original tree failed to evaluate"
    expected = expected_rescaled .* s_y

    # Apply the rewrite and evaluate on the original (unscaled) X.
    rewritten = JuliaMathSidecar.rewrite_for_unscaling(
        tree, s_x, s_y, mul_idx, binary_ops, unary_ops, options)
    actual, ok_a = SymbolicRegression.eval_tree_array(rewritten, x_test, options)
    @assert ok_a "$op_name rewritten tree failed to evaluate on unscaled X"

    # Float64 epsilon tolerance, relative to expected magnitude.
    for i in eachindex(expected)
        if !isfinite(expected[i]) || expected[i] == 0
            @test isapprox(actual[i], expected[i]; atol=1e-12)
        else
            @test abs(actual[i] - expected[i]) / abs(expected[i]) < 1e-12
        end
    end
end

@testset "per-operator round-trip identity (Lever 1, phase-24 §24.6)" begin
    T = Float64
    # Binary-only operator set first (no unary at all — covers +, -, *, /, ^).
    bin_ops = Function[(+), (-), (*), (/), (^)]
    unary_ops_empty = Function[]
    plus_idx, minus_idx, mul_idx, div_idx, pow_idx = 1, 2, 3, 4, 5

    x_2var = Matrix{Float64}(undef, 2, 5)
    x_2var[1, :] .= [1.0, 2.5, 4.0, 0.7, 3.3]
    x_2var[2, :] .= [0.5, 1.2, 2.0, 0.3, 1.8]
    x_1var = reshape([1.0, 2.5, 4.0, 0.7, 3.3], 1, 5)

    @testset "+ (binary plus)" begin
        check_roundtrip("+",
            () -> Node{T}(plus_idx, Node{T}(; feature=1), Node{T}(; feature=2)),
            x_2var, bin_ops, unary_ops_empty)
    end
    @testset "- (binary minus)" begin
        check_roundtrip("-",
            () -> Node{T}(minus_idx, Node{T}(; feature=1), Node{T}(; feature=2)),
            x_2var, bin_ops, unary_ops_empty)
    end
    @testset "* (binary multiply)" begin
        check_roundtrip("*",
            () -> Node{T}(mul_idx, Node{T}(; feature=1), Node{T}(; feature=2)),
            x_2var, bin_ops, unary_ops_empty)
    end
    @testset "/ (binary divide)" begin
        check_roundtrip("/",
            () -> Node{T}(div_idx, Node{T}(; feature=1), Node{T}(; feature=2)),
            x_2var, bin_ops, unary_ops_empty)
    end
    @testset "^ (power)" begin
        # x1 ^ 2 — squaring, magnitudes stay reasonable
        check_roundtrip("^",
            () -> Node{T}(pow_idx, Node{T}(; feature=1), Node{T}(; val=2.0)),
            x_1var, bin_ops, unary_ops_empty)
    end

    # Unary operators. `*` still required for the rewrite, so it lives
    # in binary_ops alongside `+, -`.
    bin_for_unary = Function[(+), (-), (*)]
    mul_idx_u = 3

    @testset "exp" begin
        unary_ops = Function[exp]
        # exp(x1 * 0.3) on x_1var in [0.7, 4.0] gives ~[1.2, 3.3] — safe range
        tree = () -> Node{T}(1, Node{T}(mul_idx_u, Node{T}(; feature=1), Node{T}(; val=0.3)))
        check_roundtrip("exp", tree, x_1var, bin_for_unary, unary_ops)
    end

    @testset "log" begin
        unary_ops = Function[log]
        # log(x1 + 0.5) — keeps argument > 0 for all x_1var values
        tree = () -> Node{T}(1, Node{T}(1, Node{T}(; feature=1), Node{T}(; val=0.5)))
        # log over rescaled x1+0.5 → log scaled. x1+0.5 in [1.2, 4.5] >0.
        check_roundtrip("log", tree, x_1var, bin_for_unary, unary_ops)
    end

    @testset "sqrt" begin
        unary_ops = Function[sqrt]
        tree = () -> Node{T}(1, Node{T}(; feature=1))
        # x_1var values all positive; sqrt domain ok
        check_roundtrip("sqrt", tree, x_1var, bin_for_unary, unary_ops)
    end

    @testset "abs" begin
        unary_ops = Function[abs]
        # abs(x1 - 2.0) gives mixed sign data points
        tree = () -> Node{T}(1, Node{T}(2, Node{T}(; feature=1), Node{T}(; val=2.0)))
        check_roundtrip("abs", tree, x_1var, bin_for_unary, unary_ops)
    end

    @testset "sin" begin
        unary_ops = Function[sin]
        # sin(x1) — x_1var values in [0.7, 4.0] span < 2π
        tree = () -> Node{T}(1, Node{T}(; feature=1))
        check_roundtrip("sin", tree, x_1var, bin_for_unary, unary_ops)
    end

    @testset "cos" begin
        unary_ops = Function[cos]
        tree = () -> Node{T}(1, Node{T}(; feature=1))
        check_roundtrip("cos", tree, x_1var, bin_for_unary, unary_ops)
    end
end

# ---------------------------------------------------------------------
# Lever 2 prep: parallel equation_search with disjoint Options
# (phase-24 §24.6 future-work)
# ---------------------------------------------------------------------
#
# Proves the thread-safety assumption Lever 2's ensemble fan-out will
# rely on: K independent SymbolicRegression.jl Options instances can
# share a process running concurrently without cross-contamination.
# If this ever regresses, Lever 2's fan-out cannot be built.
#
# Test only runs when Julia has >= 2 threads. The production sidecar
# starts with JULIA_NUM_THREADS=4 (see STARTUP.md), so the guard
# trips only in single-threaded local runs.

# ---------------------------------------------------------------------
# /prove_identity — SymPy WZ/Gosper round-trip (phase-25 §25.1)
# ---------------------------------------------------------------------
#
# Exercises handle_prove_identity in-process (no HTTP server needed)
# against the classical hypergeometric identities Phase 25's
# acceptance bar calls out, plus a known no-closed-form case.

function _prove(algorithm, summand, var, lower, upper)
    body = Dict("algorithm"=>algorithm, "summand"=>summand, "var"=>var,
                "lower"=>lower, "upper"=>upper)
    req = HTTP.Request("POST", "/prove_identity",
                       ["Content-Type"=>"application/json"],
                       Vector{UInt8}(JSON3.write(body)))
    resp = PyCallSidecar.handle_prove_identity(req)
    return resp.status, JSON3.read(String(copy(resp.body)))
end

@testset "/prove_identity classical identities (phase-25 §25.1)" begin

    @testset "Σ k on [0,n] via Gosper = n(n+1)/2" begin
        status, body = _prove("gosper", "k", "k", "0", "n")
        @test status == 200
        @test body[:ok] == true
        @test body[:status] == "proved"
        # SymPy may render as `n*(n + 1)/2` or `n*(n+1)/2`; check for both
        # by normalising spaces.
        result_nospace = replace(String(body[:result]), " " => "")
        @test result_nospace == "n*(n+1)/2"
        @test body[:algorithm_used] == "gosper"
        @test occursin("k", String(body[:certificate]))  # antidifference references k
    end

    @testset "Σ binomial(n,k) on [0,n] via auto = 2^n" begin
        status, body = _prove("auto", "binomial(n, k)", "k", "0", "n")
        @test status == 200
        @test body[:status] == "proved"
        @test String(body[:result]) == "2**n"
        # Auto fell through Gosper to WZ
        @test body[:algorithm_used] == "zeilberger_wz"
    end

    @testset "Σ k·binomial(n,k) on [0,n] via auto = n·2^(n-1)" begin
        status, body = _prove("auto", "k * binomial(n, k)", "k", "0", "n")
        @test status == 200
        @test body[:status] == "proved"
        # SymPy renders this as `2**(n - 1)*n`
        @test occursin("2**(n - 1)*n", String(body[:result])) ||
              occursin("n*2**(n - 1)", String(body[:result]))
    end

    @testset "Σ binomial(n,k)² on [0,n] via auto = C(2n,n)" begin
        status, body = _prove("auto", "binomial(n, k)**2", "k", "0", "n")
        @test status == 200
        @test body[:status] == "proved"
        # SymPy returns the gamma-function form: 4^n * Γ(n+1/2) / (√π * Γ(n+1))
        # which is mathematically equal to C(2n,n).
        result = String(body[:result])
        @test occursin("4**n", result) && occursin("gamma", result)
    end

    @testset "Σ r^k on [0,n] via auto = (r^(n+1)-1)/(r-1)" begin
        status, body = _prove("auto", "r**k", "k", "0", "n")
        @test status == 200
        @test body[:status] == "proved"
        # Gosper finds this cleanly: r**k/(r - 1) is the antidifference
        @test body[:algorithm_used] == "gosper"
        @test occursin("(r - 1)", String(body[:result]))
    end

    @testset "Σ 1/(k·(k+1)) on [1,n] via Gosper = n/(n+1) (telescoping)" begin
        status, body = _prove("gosper", "1/(k*(k+1))", "k", "1", "n")
        @test status == 200
        @test body[:status] == "proved"
        @test String(body[:result]) == "n/(n + 1)"
    end

    @testset "Σ 1/k² on [1,n] correctly returns no_closed_form" begin
        # harmonic(n, 2) is just notation for the unevaluated sum; we
        # reject it as not a real closed form.
        status, body = _prove("auto", "1/k**2", "k", "1", "n")
        @test status == 200
        @test body[:status] == "no_closed_form"
        @test body[:result] === nothing
    end

end

@testset "/prove_identity input validation (phase-25 §25.1)" begin
    # Missing required field
    status, body = _prove("auto", "", "k", "0", "n")
    @test status == 400
    @test body[:ok] == false
    @test occursin("Missing", String(body[:explanation]))

    # Unknown algorithm
    status, body = _prove("nonexistent", "k", "k", "0", "n")
    @test status == 400
    @test body[:ok] == false

    # var must be identifier
    status, body = _prove("gosper", "k", "k+1", "0", "n")
    @test status == 400
    @test body[:ok] == false

    # Forbidden pattern
    status, body = _prove("auto", "__import__('os').system('rm -rf /')",
                          "k", "0", "n")
    @test status == 400
    @test body[:ok] == false
    @test body[:error] == "safety_reject"
end

# ---------------------------------------------------------------------
# /recognise/constant — PSLQ integer-relation search (phase-26 §26.2)
# ---------------------------------------------------------------------
#
# Exercises handle_recognise_constant in-process. No outbound network
# (mpmath-only) so these can run in CI without the OEIS dependency
# that /recognise/sequence carries.

function _recognise_constant(value, basis="BASIC", precision=200)
    body = Dict("value"=>value, "basis"=>basis, "precisionDigits"=>precision)
    req = HTTP.Request("POST", "/recognise/constant",
                       ["Content-Type"=>"application/json"],
                       Vector{UInt8}(JSON3.write(body)))
    resp = PyCallSidecar.handle_recognise_constant(req)
    return resp.status, JSON3.read(String(copy(resp.body)))
end

# Use precise 99-digit truncations of the named constants. The
# input-precision-aware tol picks a tolerance below the input's
# noise floor (~1e-90), which is what real callers will get when
# they round-trip a constant through 100-digit decimal storage.

@testset "/recognise/constant classical constants (phase-26 §26.2)" begin

    @testset "ζ(3) via BASIC basis" begin
        status, body = _recognise_constant(
            "1.20205690315959428539973816151144999076498629234049888179227155534183820578631309018645587360933525")
        @test status == 200
        @test body[:ok] == true
        @test body[:recognized] == true
        @test String(body[:name]) == "zeta(3)"
        @test String(body[:basis_used]) == "BASIC"
    end

    @testset "π via BASIC" begin
        status, body = _recognise_constant(
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706")
        @test status == 200 && body[:recognized] == true
        @test String(body[:name]) == "pi"
    end

    @testset "e via BASIC" begin
        status, body = _recognise_constant(
            "2.71828182845904523536028747135266249775724709369995957496696762772407663035354759457138217852516642")
        @test body[:recognized] == true
        @test String(body[:name]) == "e"
    end

    @testset "γ (Euler-Mascheroni) via BASIC" begin
        status, body = _recognise_constant(
            "0.57721566490153286060651209008240243104215933593992359880576723488486772677766467093694706329174674")
        @test body[:recognized] == true
        @test String(body[:name]) == "gamma"
    end

    @testset "π² via BASIC (direct basis element)" begin
        status, body = _recognise_constant(
            "9.86960440108935861883449099987615113531369940724079062641334937622004482241920524300177340371855224")
        @test body[:recognized] == true
        @test String(body[:name]) == "pi^2"
    end

    @testset "Catalan via PHYSICS basis" begin
        status, body = _recognise_constant(
            "0.91596559417721901505460351493238411077414937428167213426649811962176301977625476947935651292611510",
            "PHYSICS")
        @test body[:recognized] == true
        @test String(body[:name]) == "catalan"
        @test String(body[:basis_used]) == "PHYSICS"
    end

    @testset "Catalan via BASIC (NOT in basis) returns no_match" begin
        # Catalan isn't in BASIC, so PSLQ should find no clean relation.
        status, body = _recognise_constant(
            "0.91596559417721901505460351493238411077414937428167213426649811962176301977625476947935651292611510",
            "BASIC")
        @test body[:recognized] == false
    end

    @testset "Random transcendental returns no_match" begin
        # Made-up digits — no chance this fits an integer relation in
        # the BASIC basis. Negative-result correctness is critical
        # because a false positive would hallucinate a citation.
        status, body = _recognise_constant(
            "0.78901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234")
        @test body[:recognized] == false
    end
end

@testset "/recognise/constant input validation (phase-26 §26.2)" begin
    # Missing value
    status, body = _recognise_constant("")
    @test status == 400
    @test body[:ok] == false

    # Unknown basis
    status, body = _recognise_constant("3.14159", "MYSTERY_BASIS")
    @test status == 400
    @test body[:ok] == false

    # Safety reject: non-decimal string can't reach mpmath
    status, body = _recognise_constant("__import__('os').system('rm')")
    @test status == 400
    @test body[:ok] == false
    @test String(body[:error]) == "safety_reject"

    # Exponent notation is permitted
    status, body = _recognise_constant("3.14159265358979e0")
    @test status == 200
    @test body[:ok] == true
    # (Whether it recognises as π depends on the digit count — just
    # confirming the safety filter didn't reject the input.)
end

# ---------------------------------------------------------------------
# /recognise/minpoly — minimal polynomial recovery (phase-26 §26.3)
# ---------------------------------------------------------------------

function _recognise_minpoly(value, degreeBound=4, precision=200)
    body = Dict("value"=>value, "degreeBound"=>degreeBound,
                "precisionDigits"=>precision)
    req = HTTP.Request("POST", "/recognise/minpoly",
                       ["Content-Type"=>"application/json"],
                       Vector{UInt8}(JSON3.write(body)))
    resp = PyCallSidecar.handle_recognise_minpoly(req)
    return resp.status, JSON3.read(String(copy(resp.body)))
end

@testset "/recognise/minpoly classical algebraics (phase-26 §26.3)" begin

    @testset "√2 + √3 → x^4 - 10*x^2 + 1 (canonical §26.3 case)" begin
        status, body = _recognise_minpoly(
            "3.14626436994197234232913506571557044551247712918732870123248671744266549537090707593153372",
            4)
        @test status == 200
        @test body[:ok] == true
        @test body[:recognized] == true
        @test String(body[:minPoly]) == "x^4 - 10*x^2 + 1"
        @test body[:degree] == 4
        @test Int.(body[:coefficients]) == [1, 0, -10, 0, 1]
    end

    @testset "√2 → x^2 - 2" begin
        status, body = _recognise_minpoly(
            "1.41421356237309504880168872420969807856967187537694807317667973799073247846210703885038753",
            2)
        @test body[:recognized] == true
        @test String(body[:minPoly]) == "x^2 - 2"
        @test body[:degree] == 2
        @test Int.(body[:coefficients]) == [-2, 0, 1]
    end

    @testset "golden ratio (1+√5)/2 → x^2 - x - 1" begin
        status, body = _recognise_minpoly(
            "1.6180339887498948482045868343656381177203091798057628621354486227052604628189024497072072",
            2)
        @test body[:recognized] == true
        @test String(body[:minPoly]) == "x^2 - x - 1"
        @test Int.(body[:coefficients]) == [-1, -1, 1]
    end

    @testset "∛2 → x^3 - 2" begin
        status, body = _recognise_minpoly(
            "1.25992104989487316476721060727822835057025146470150798008197511215529967651395948372939656",
            3)
        @test body[:recognized] == true
        @test String(body[:minPoly]) == "x^3 - 2"
        @test body[:degree] == 3
    end

    @testset "π → no_match at degree 4 (transcendental)" begin
        status, body = _recognise_minpoly(
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482",
            4)
        @test body[:recognized] == false
    end

    @testset "leading coefficient normalised positive" begin
        # The √2 case at degree 2 — PSLQ might return ±(a0, a1, a2);
        # the handler normalises so a_d > 0.
        status, body = _recognise_minpoly(
            "1.41421356237309504880168872420969807856967187537694807317667973799073247846210703885038753",
            2)
        coeffs = Int.(body[:coefficients])
        @test coeffs[end] > 0  # leading coefficient must be positive
    end
end

@testset "/recognise/minpoly input validation (phase-26 §26.3)" begin
    # Missing value
    status, body = _recognise_minpoly("")
    @test status == 400 && body[:ok] == false

    # Safety reject
    status, body = _recognise_minpoly("__import__('os').system('rm')")
    @test status == 400
    @test String(body[:error]) == "safety_reject"

    # Out-of-range degreeBound clamps silently (doesn't reject) — both
    # degree=0 and degree=99 land within [MIN_LLL_DEGREE, MAX_LLL_DEGREE].
    status, body = _recognise_minpoly(
        "1.41421356237309504880168872420969807856967187537694807317667973799073247846210703885038753",
        99)
    @test status == 200
    @test body[:ok] == true  # clamped to MAX_LLL_DEGREE=12, recognises fine
end

@testset "render_polynomial (phase-26 §26.3)" begin
    @test PyCallSidecar.render_polynomial([1, 0, -10, 0, 1]) == "x^4 - 10*x^2 + 1"
    @test PyCallSidecar.render_polynomial([-2, 0, 1]) == "x^2 - 2"
    @test PyCallSidecar.render_polynomial([-1, -1, 1]) == "x^2 - x - 1"
    @test PyCallSidecar.render_polynomial([-2, 0, 0, 1]) == "x^3 - 2"
    @test PyCallSidecar.render_polynomial([5]) == "5"
    @test PyCallSidecar.render_polynomial([0, 0, 1]) == "x^2"
    @test PyCallSidecar.render_polynomial([1, -3, 0, 2]) == "2*x^3 - 3*x + 1"
end

@testset "estimate_input_precision (phase-26 §26.2)" begin
    # Helper directly — verifies the tolerance heuristic.
    @test PyCallSidecar.estimate_input_precision("3.14159") == 6
    @test PyCallSidecar.estimate_input_precision("3.14159e0") == 6
    @test PyCallSidecar.estimate_input_precision("0.5") == 1
    @test PyCallSidecar.estimate_input_precision("100") == 3
    @test PyCallSidecar.estimate_input_precision("0.0001") == 4
    @test PyCallSidecar.estimate_input_precision("-1.234") == 4
    # 99-digit value used in the classical-constants tests
    big = "1.20205690315959428539973816151144999076498629234049888179227155534183820578631309018645587360933525"
    @test PyCallSidecar.estimate_input_precision(big) == 99
end

@testset "parallel equation_search safety (Lever 2 prep, phase-24 §24.6)" begin
    if Threads.nthreads() < 2
        @test_skip "needs --threads >= 2 to actually parallelise"
    else
        n = 20
        xa1 = collect(range(0.0, 5.0; length=n))
        xa2 = collect(range(1.0, 3.0; length=n))
        Xa = vcat(xa1', xa2')
        ya = xa1 .+ xa2

        xb1 = collect(range(0.5, 2.0; length=n))
        xb2 = collect(range(1.0, 4.0; length=n))
        Xb = vcat(xb1', xb2')
        yb = xb1 .* xb2

        ops_strs   = String["+", "-", "*"]
        binary_ops = Function[(+), (-), (*)]
        unary_ops  = Function[]

        task_a = Threads.@spawn JuliaMathSidecar.run_pysr_pass(Xa, ya;
            auto_normalize=false, ops_strs=ops_strs, binary_ops=binary_ops,
            unary_ops=unary_ops, var_names_str=String["x1", "x2"],
            populations=4, iterations=8, max_complexity=15, seed=42)

        task_b = Threads.@spawn JuliaMathSidecar.run_pysr_pass(Xb, yb;
            auto_normalize=false, ops_strs=ops_strs, binary_ops=binary_ops,
            unary_ops=unary_ops, var_names_str=String["x1", "x2"],
            populations=4, iterations=8, max_complexity=15, seed=7)

        front_a, n_a = fetch(task_a)
        front_b, n_b = fetch(task_b)

        @test n_a > 0 && !isempty(front_a)
        @test n_b > 0 && !isempty(front_b)
        @test isfinite(minimum(Float64(e["mse"]) for e in front_a))
        @test isfinite(minimum(Float64(e["mse"]) for e in front_b))
    end
end

# ---------------------------------------------------------------------
# Lever 2 — resource-adaptive ensemble (phase-24 §24.6)
# ---------------------------------------------------------------------

@testset "select_ensemble_variants tiering (Lever 2, phase-24 §24.6)" begin
    ops = String["+","-","*","/","^","exp","log"]
    vnames(c) = [v.name for v in JuliaMathSidecar.select_ensemble_variants(c, ops)]
    # §24.6 tier table — tiers round DOWN to 1/2/4/8/16-core thresholds.
    @test vnames(1)  == ["normalized"]
    @test vnames(2)  == ["normalized"]
    @test vnames(3)  == ["normalized"]                          # below 4 → 1-2 tier
    @test vnames(4)  == ["normalized","raw"]
    @test vnames(7)  == ["normalized","raw"]
    @test vnames(8)  == ["normalized","raw","log"]
    @test vnames(15) == ["normalized","raw","log"]
    @test vnames(16) == ["normalized","raw","log","opsubset"]
    @test vnames(64) == ["normalized","raw","log","opsubset"]
    # The opsubset island drops transcendental ops, keeps the arithmetic core.
    v16 = JuliaMathSidecar.select_ensemble_variants(16, ops)
    opsub = v16[findfirst(v -> v.kind == :opsubset, v16)]
    @test "exp" ∉ opsub.operators && "log" ∉ opsub.operators
    @test "*" in opsub.operators && "^" in opsub.operators
end

@testset "ensemble_operator_subset (Lever 2, phase-24 §24.6)" begin
    @test JuliaMathSidecar.ensemble_operator_subset(
        String["+","*","^","exp","log","sqrt","sin"]) == ["+","*","^"]
    # No arithmetic core → fall back to the full set so the island still runs.
    @test JuliaMathSidecar.ensemble_operator_subset(String["exp","log"]) == ["exp","log"]
end

@testset "run_pysr_log_pass recovers a power law (Lever 2, phase-24 §24.6)" begin
    # y = 3·x²  log-linearises to  log y = log 3 + 2·log x — a relation
    # the GP can find with +/* alone. Proves the exp(·)∘log round-trip
    # produces an expression whose mse on the ORIGINAL (unlogged) data is
    # small, not just in log-space.
    n = 16
    x = collect(range(1.2, 6.0; length=n))
    y = 3.0 .* x .^ 2
    X = reshape(x, 1, n)
    front, evaluated = JuliaMathSidecar.run_pysr_log_pass(X, y;
        ops_strs=String["+","-","*"], binary_ops=Function[(+),(-),(*)],
        unary_ops=Function[], var_names_str=String["x"],
        populations=8, iterations=30, max_complexity=12, seed=11)
    @test evaluated > 0
    @test !isempty(front)
    @test !JuliaMathSidecar.fit_looks_stuck(front, y)  # genuine recovery, not noise
end

@testset "run_pysr_log_pass skips non-positive data (Lever 2, phase-24 §24.6)" begin
    # log needs strictly positive X and y; a sign-spanning sweep makes the
    # island inapplicable — it returns an empty front rather than erroring,
    # and the ensemble merge just drops it.
    n = 8
    x = collect(range(-2.0, 5.0; length=n))
    y = x .^ 2 .+ 1.0
    X = reshape(x, 1, n)
    front, evaluated = JuliaMathSidecar.run_pysr_log_pass(X, y;
        ops_strs=String["+","-","*"], binary_ops=Function[(+),(-),(*)],
        unary_ops=Function[], var_names_str=String["x"],
        populations=4, iterations=8, max_complexity=10, seed=3)
    @test isempty(front)
    @test evaluated == 0
end

@testset "run_pysr_ensemble unions islands (Lever 2, phase-24 §24.6)" begin
    n = 14
    x = collect(range(1.0, 5.0; length=n))
    y = 2.0 .* x                       # all islands (incl. log) can fit this
    X = reshape(x, 1, n)
    variants = JuliaMathSidecar.select_ensemble_variants(8, String["+","-","*"])
    @test length(variants) == 3
    merged, total_eval, ran = JuliaMathSidecar.run_pysr_ensemble(X, y;
        variants=variants, var_names_str=String["x"],
        populations=4, iterations=12, max_complexity=10, seed=5,
        per_variant_timeout_ms=60_000)
    @test total_eval > 0
    @test !isempty(merged)
    @test !isempty(ran)                              # at least one island returned
    @test !JuliaMathSidecar.fit_looks_stuck(merged, y)
    # merged front is deduped by expr and sorted by complexity ascending.
    comps = [Int(e["complexity"]) for e in merged]
    @test issorted(comps)
    exprs = [String(e["expr"]) for e in merged]
    @test length(exprs) == length(unique(exprs))     # union deduped
end

@testset "handle_pysr autoEnsemble path (Lever 2, phase-24 §24.6)" begin
    n = 12
    rows = [[x, 2.0 * x] for x in range(1.0, 5.0; length=n)]
    body = Dict("samples"=>rows, "varNames"=>["x"], "targetName"=>"y",
                "operators"=>["+","-","*"], "autoEnsemble"=>true,
                "ensembleCores"=>8, "populations"=>4, "iterations"=>12,
                "complexityCeiling"=>10, "seed"=>5)
    req = HTTP.Request("POST", "/pysr",
        ["Content-Type"=>"application/json"], Vector{UInt8}(JSON3.write(body)))
    resp = JuliaMathSidecar.handle_pysr(req)
    @test resp.status == 200
    payload = JSON3.read(String(copy(resp.body)))
    @test payload["ok"] == true
    @test payload["ensemble"] == true
    @test payload["retried"] == false                # ensemble supersedes autoRetry
    @test !isempty(payload["pareto"])
    @test !isempty(payload["variants"])
end

# ---------------------------------------------------------------------
# Integration — HTTP surface
# ---------------------------------------------------------------------
# Number-theory primitives — unit tests (phase-28 §28.1)
# ---------------------------------------------------------------------
#
# Pure-function tests. No server needed. Validates parity with the JVM
# `MathConjectureSubstrate` implementation at the per-primitive level
# before the integration test exercises the HTTP route.

@testset "julia_aliases — Mathematica-flavoured globals (paper-review 2026-05-22)" begin
    # Numeric constants — bare-name resolution.
    @test JuliaMathSidecar.PI == π
    @test JuliaMathSidecar.PLANCK_H ≈ 6.62607015e-34
    @test JuliaMathSidecar.σ_F     ≈ 5.670374419e-8
    @test JuliaMathSidecar.SIGMA_SB == JuliaMathSidecar.σ_F
    @test JuliaMathSidecar.BOLTZMANN_K ≈ 1.380649e-23
    @test JuliaMathSidecar.SPEED_OF_LIGHT == 299_792_458.0
    @test JuliaMathSidecar.GRAVITATIONAL_CONSTANT ≈ 6.67430e-11
    @test JuliaMathSidecar.ELEMENTARY_CHARGE ≈ 1.602176634e-19

    # isprime — fast-path sieve + trial-division fallback.
    @test JuliaMathSidecar.isprime(2)
    @test JuliaMathSidecar.isprime(97)
    @test JuliaMathSidecar.isprime(1_000_003)            # in sieve range
    @test !JuliaMathSidecar.isprime(1)
    @test !JuliaMathSidecar.isprime(100)
    @test !JuliaMathSidecar.isprime(1_000_002)
    # Above NT_MAX_N (2_000_000) — exercises the trial-division branch.
    @test JuliaMathSidecar.isprime(2_000_003)            # large prime
    @test !JuliaMathSidecar.isprime(2_000_004)           # large composite
end

@testset "number-theory primitives parity" begin
    # SPF / isPrime
    @test JuliaMathSidecar.nt_is_prime(2)
    @test JuliaMathSidecar.nt_is_prime(97)
    @test !JuliaMathSidecar.nt_is_prime(1)
    @test !JuliaMathSidecar.nt_is_prime(91)   # 7 × 13
    @test !JuliaMathSidecar.nt_is_prime(100)

    # omega / bigOmega
    @test JuliaMathSidecar.nt_omega(12)     == 2  # {2, 3}
    @test JuliaMathSidecar.nt_big_omega(12) == 3  # 2 × 2 × 3
    @test JuliaMathSidecar.nt_omega(1)      == 0
    @test JuliaMathSidecar.nt_big_omega(1)  == 0

    # sigma — sum of divisors
    @test JuliaMathSidecar.nt_sigma(6)  == 12  # 1 + 2 + 3 + 6
    @test JuliaMathSidecar.nt_sigma(12) == 28  # 1+2+3+4+6+12
    @test JuliaMathSidecar.nt_sigma(1)  == 1

    # divisor count τ(n)
    @test JuliaMathSidecar.nt_divisor_count(6)  == 4
    @test JuliaMathSidecar.nt_divisor_count(12) == 6
    @test JuliaMathSidecar.nt_divisor_count(36) == 9

    # Möbius μ(n)
    @test JuliaMathSidecar.nt_mobius(1)  == 1
    @test JuliaMathSidecar.nt_mobius(2)  == -1
    @test JuliaMathSidecar.nt_mobius(6)  == 1
    @test JuliaMathSidecar.nt_mobius(4)  == 0   # 2² → 0
    @test JuliaMathSidecar.nt_mobius(12) == 0   # 2² × 3 → 0
    @test JuliaMathSidecar.nt_mobius(30) == -1  # 2 × 3 × 5

    # Happy numbers — known set in [1, 50]: {1, 7, 10, 13, 19, 23, 28,
    # 31, 32, 44, 49, ...}. Spot-check.
    @test JuliaMathSidecar.nt_is_happy(1)
    @test JuliaMathSidecar.nt_is_happy(7)
    @test JuliaMathSidecar.nt_is_happy(19)
    @test !JuliaMathSidecar.nt_is_happy(2)
    @test !JuliaMathSidecar.nt_is_happy(11)
end

@testset "nt_mertens: dense vs segmented (Phase 30 spike)" begin
    M_dense = JuliaMathSidecar.nt_mertens_dense
    M_seg   = JuliaMathSidecar.nt_mertens_segmented

    # Known M(10^n) — OEIS A084237. Both paths must reproduce these.
    @test M_dense(1)         == 1
    @test M_dense(10)        == -1
    @test M_dense(100)       == 1
    @test M_dense(1_000)     == 2
    @test M_dense(10_000)    == -23
    @test M_dense(100_000)   == -48
    @test M_dense(1_000_000) == 212        # the famous M(10^6)
    @test M_seg(1)           == 1          # μ(1) = 1 boundary
    @test M_seg(2)           == 0
    @test M_seg(1_000_000)   == 212

    # The trust gate: segmented == dense EXACTLY across the overlap range,
    # including non-round offsets (guards against round-number flukes) and
    # the NT_MAX_N boundary.
    for k in (1, 2, 10, 999, 1_000, 10_000, 100_000, 999_983, 1_000_000,
              1_234_567, 1_500_000, 2_000_000)
        @test M_seg(k) == M_dense(k)
    end

    # Beyond NT_MAX_N: dense refuses, segmented extends and still matches
    # the OEIS reference at 10^7 (this is the whole point of the spike).
    @test_throws ArgumentError M_dense(10_000_000)
    @test M_seg(10_000_000) == 1037        # M(10^7), A084237
    # √x > NT_MAX_N must be rejected (no primes to sieve with).
    @test_throws ArgumentError M_seg((JuliaMathSidecar.NT_MAX_N + 1)^2)
end

@testset "nt_evaluate_window: keyed ordering + spot values" begin
    pairs = JuliaMathSidecar.nt_evaluate_window(100, 50)
    @test length(pairs) == 9
    # Ordering matches the JVM `LinkedHashMap` exactly.
    @test [first(p) for p in pairs] == [
        "abundant_density", "deficient_density",
        "prime_density",    "happy_density",
        "mean_omega",       "mean_Omega",
        "mean_divisor_count", "max_divisor_count",
        "mertens_delta",
    ]
    pmap = Dict(pairs)
    # Ten primes in [100, 150) → density 0.2.
    @test isapprox(pmap["prime_density"], 10 / 50; atol=1e-12)
    # Densities sum to ≤ 1 (abundant + deficient ≤ 1; perfect numbers
    # 6, 28, 496 are excluded — none in [100, 150)).
    @test pmap["abundant_density"] + pmap["deficient_density"] ≈ 1.0
end

# ---------------------------------------------------------------------
#
# We use a random ephemeral port (port=0 makes the OS pick one) to
# avoid collisions with any sidecar the developer may already be
# running on 8088. The server thread runs as @async; we sleep briefly
# to let it bind before issuing requests.

# Pick a random free port. Reuse pattern: bind a temporary socket to
# port 0 (OS-assigned), read back the port number, close immediately.
# Race-y in theory but fine for a single-threaded test setup.
function pick_free_port()
    sock = Sockets.listen(IPv4("127.0.0.1"), 0)
    p = Sockets.getsockname(sock)[2]
    close(sock)
    return p
end

using Sockets

@testset "HTTP integration" begin
    port = pick_free_port()
    @info "test server starting" port
    server_task = @async JuliaMathSidecar.start_server!(host="127.0.0.1", port=port)
    sleep(2.0)  # give the server time to bind + JIT-compile route handlers

    base = "http://127.0.0.1:$port"

    @testset "/healthz returns ok" begin
        r = HTTP.get("$base/healthz"; status_exception=false)
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test body[:status] == "ready"
    end

    @testset "/eval simple arithmetic" begin
        r = HTTP.post("$base/eval";
            body = JSON3.write(Dict(:expr => "floor(2.04e-6 / 400e-9)")),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        # Note: floor returns a Float64 since the input was a float division.
        # The result is stringified by handle_eval.
        @test parse(Float64, body[:result]) == 5.0
    end

    @testset "/eval rejects forbidden pattern" begin
        r = HTTP.post("$base/eval";
            body = JSON3.write(Dict(:expr => "run(`ls`)")),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 400
        body = JSON3.read(r.body)
        @test body[:ok] == false
        @test body[:error] == "safety_rejected"
    end

    @testset "/nsolve quadratic" begin
        # f(x) = x^2 - 4, initial guess 1.0 → should converge to 2.0
        r = HTTP.post("$base/nsolve";
            body = JSON3.write(Dict(:expr => "x^2 - 4", :var => "x", :x0 => 1.0)),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test isapprox(body[:result], 2.0; atol=1e-6)
    end

    @testset "/integrate sin from 0 to π" begin
        # ∫₀^π sin(x) dx = 2 exactly
        r = HTTP.post("$base/integrate";
            body = JSON3.write(Dict(
                :expr => "sin(x)", :var => "x",
                :a => 0.0, :b => "π",
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test isapprox(body[:result], 2.0; atol=1e-6)
    end

    @testset "/sweep returns stats" begin
        # x^2 over [1, 10], 10 samples → mean ≈ 38.5, max 100
        r = HTTP.post("$base/sweep";
            body = JSON3.write(Dict(
                :expr => "x^2", :var => "x",
                :start => 1, :end => 10, :samples => 10,
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test body[:n] == 10
        @test body[:max] == 100.0
    end

    @testset "/metrics surfaces counters" begin
        r = HTTP.get("$base/metrics"; status_exception=false)
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test haskey(body, :endpoints)
        # By now we've hit /eval, /nsolve, /integrate, /sweep, /healthz at least once.
        @test body[:endpoints][:eval][:count_total] >= 1
    end

    # ─────────────────────────────────────────────────────────────────
    # /pysr — symbolic regression (phase-24 §24.1)
    # ─────────────────────────────────────────────────────────────────
    #
    # Two shape tests + one acceptance test. The acceptance test feeds
    # a synthetic f(x, y) = x * y dataset and expects the simplest
    # Pareto entry to recover that law. Tagged with a generous timeout
    # because the first /pysr call also JIT-compiles SymbolicRegression.

    @testset "/pysr rejects unknown operator" begin
        r = HTTP.post("$base/pysr";
            body = JSON3.write(Dict(
                :samples => [[1.0, 2.0, 2.0], [2.0, 3.0, 6.0], [3.0, 4.0, 12.0]],
                :varNames => ["x", "y"],
                :targetName => "f",
                :operators => ["+", "tanh"],
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 400
        body = JSON3.read(r.body)
        @test body[:ok] == false
        @test body[:error] == "safety_rejected"
        @test body[:pattern] == "tanh"
    end

    @testset "/pysr enforces shape caps" begin
        # Row width mismatch: varNames says 2 vars, row has 4 fields.
        r = HTTP.post("$base/pysr";
            body = JSON3.write(Dict(
                :samples => [[1.0, 2.0, 3.0, 4.0]],
                :varNames => ["x", "y"],
                :targetName => "f",
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 400
        body = JSON3.read(r.body)
        @test body[:ok] == false
        @test body[:error] == "bad_request"
    end

    @testset "/pysr recovers f(x,y) = x*y from synthetic data" begin
        # Build a small grid: x ∈ 1:6, y ∈ 1:6, target = x*y. 36 rows.
        rows = Vector{Vector{Float64}}()
        for x in 1:6, y in 1:6
            push!(rows, [Float64(x), Float64(y), Float64(x*y)])
        end
        r = HTTP.post("$base/pysr";
            body = JSON3.write(Dict(
                :samples => rows,
                :varNames => ["x", "y"],
                :targetName => "f",
                :operators => ["+", "-", "*"],
                # Keep the search tiny so the test runs fast.
                :populations => 4,
                :iterations => 8,
                :timeoutMs => 60_000,
                :seed => 42,
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
            readtimeout = 120,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test haskey(body, :pareto)
        @test !isempty(body[:pareto])
        # Pareto front must be complexity-ascending.
        complexities = [Int(e[:complexity]) for e in body[:pareto]]
        @test complexities == sort(complexities)
        # At least one entry must have near-zero mse — the law x*y is
        # reachable under the {+, -, *} operator set on this exact grid.
        min_mse = minimum(Float64(e[:mse]) for e in body[:pareto])
        @test min_mse < 1e-6
    end

    # ─────────────────────────────────────────────────────────────────
    # /numbertheory/window — port of MathConjectureSubstrate (phase-28 §28.1)
    # ─────────────────────────────────────────────────────────────────

    @testset "/numbertheory/window returns ordered scalar pairs" begin
        # Window [100, 150): a deterministic, hand-checkable range.
        r = HTTP.post("$base/numbertheory/window";
            body = JSON3.write(Dict(:start => 100, :size => 50)),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
            readtimeout = 30,
        )
        @test r.status == 200
        body = JSON3.read(r.body)
        @test body[:ok] == true
        @test body[:start] == 100
        @test body[:size] == 50
        # `result` is an ordered list of [key, value] pairs.
        @test length(body[:result]) == 9
        keys_in_order = [String(e[1]) for e in body[:result]]
        @test keys_in_order == [
            "abundant_density",   "deficient_density",
            "prime_density",      "happy_density",
            "mean_omega",         "mean_Omega",
            "mean_divisor_count", "max_divisor_count",
            "mertens_delta",
        ]
        # Spot-check primes in [100, 150): {101, 103, 107, 109, 113, 127,
        # 131, 137, 139, 149} — ten primes over 50 integers → density 0.2.
        result_map = Dict(String(e[1]) => Float64(e[2]) for e in body[:result])
        @test isapprox(result_map["prime_density"], 10 / 50; atol=1e-12)
    end

    @testset "/numbertheory/window rejects out-of-range window" begin
        r = HTTP.post("$base/numbertheory/window";
            body = JSON3.write(Dict(
                :start => JuliaMathSidecar.NT_MAX_N,
                :size  => 100,
            )),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 400
        body = JSON3.read(r.body)
        @test body[:ok] == false
        @test body[:error] == "bad_request"
    end

    @testset "/numbertheory/window rejects negative size" begin
        r = HTTP.post("$base/numbertheory/window";
            body = JSON3.write(Dict(:start => 10, :size => -5)),
            headers = ["Content-Type" => "application/json"],
            status_exception = false,
        )
        @test r.status == 400
        body = JSON3.read(r.body)
        @test body[:ok] == false
        @test body[:error] == "bad_request"
    end

    # ─────────────────────────────────────────────────────────────────
    # Corpus fixtures — regression cases derived from real LLM tool
    # calls captured by EmergentTerrain under research/a3289005/.
    # See corpus_fixtures.jl for the full case-by-case provenance.
    # ─────────────────────────────────────────────────────────────────
    include("corpus_fixtures.jl")
    run_corpus_fixtures(base)

    # Stop the server. The task will exit on the close() called inside
    # start_server!'s finally block — but we have to interrupt it first
    # since wait(server) blocks. Easiest: just let it die when the
    # test process exits.
end
