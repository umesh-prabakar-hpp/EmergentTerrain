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

# ---------------------------------------------------------------------
# Integration — HTTP surface
# ---------------------------------------------------------------------
# Number-theory primitives — unit tests (phase-28 §28.1)
# ---------------------------------------------------------------------
#
# Pure-function tests. No server needed. Validates parity with the JVM
# `MathConjectureSubstrate` implementation at the per-primitive level
# before the integration test exercises the HTTP route.

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
