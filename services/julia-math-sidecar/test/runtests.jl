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

# ---------------------------------------------------------------------
# Integration — HTTP surface
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
