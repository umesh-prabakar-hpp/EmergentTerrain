# =====================================================================
# test/discover_endpoint_test.jl — fork 2b Phase 2: /discover/validate.
#
# Exercises the handler directly (no server start needed): builds an
# HTTP.Request, calls JuliaMathSidecar.handle_discover_validate, asserts
# the response. Covers the ENV gate (dormant by default), accept/reject
# through the sandbox, AST containment, and out-of-range rejection.
# Requires the package (recompiles it) — run with sidecars stopped.
# =====================================================================
using Test
using HTTP
using JSON3
using JuliaMathSidecar

const _CORRECT = """
function candidate(x::Integer; block::Integer = 1_000_000)::Int64
    x < 1 && return Int64(0)
    root = isqrt(x); is_composite = falses(root + 1); primes = Int[]
    for i in 2:root
        if !is_composite[i]
            push!(primes, i); j = i * i
            while j <= root; is_composite[j] = true; j += i; end
        end
    end
    total = Int64(0); mu = Vector{Int8}(undef, block); rem = Vector{Int}(undef, block); lo = 1
    while lo <= x
        hi = min(lo + block - 1, x); n = hi - lo + 1
        for i in 1:n; mu[i] = Int8(1); rem[i] = lo + i - 1; end
        for p in primes
            start = cld(lo, p) * p; i = start - lo + 1
            while i <= n; mu[i] = -mu[i]; rem[i] = div(rem[i], p); i += p; end
            psq = p * p; startsq = cld(lo, psq) * psq; i = startsq - lo + 1
            while i <= n; mu[i] = Int8(0); i += psq; end
        end
        for i in 1:n
            if rem[i] > 1; mu[i] = -mu[i]; end
            total += mu[i]
        end
        lo = hi + 1
    end
    return total
end
"""
const _BUGGY = replace(_CORRECT, "if rem[i] > 1; mu[i] = -mu[i]; end\n" => "")

_post(payload) = HTTP.Request("POST", "/discover/validate",
    ["Content-Type" => "application/json"], JSON3.write(payload))
_call(payload) = JuliaMathSidecar.handle_discover_validate(_post(payload))
_json(resp) = JSON3.read(String(resp.body))

@testset "fork 2b Phase 2 — /discover/validate" begin
    cps = [1, 2, 10, 1_000, 100_000, 1_000_000]

    @testset "dormant by default (ENV unset → 403)" begin
        delete!(ENV, "ET_DISCOVER_EXEC")
        resp = _call(Dict("code" => _CORRECT, "checkpoints" => cps))
        @test resp.status == 403
        @test _json(resp)["error"] == "disabled"
    end

    ENV["ET_DISCOVER_EXEC"] = "1"
    try
        @testset "ACCEPT a correct candidate (validates target beyond oracle)" begin
            resp = _call(Dict("code" => _CORRECT, "checkpoints" => cps,
                "anchors" => Dict("10000000" => 1037), "target" => 100_000_000,
                "cpuSeconds" => 20, "wallSeconds" => 28))
            @test resp.status == 200
            b = _json(resp)
            @test b["status"] == "ok"
            @test b["verdict"] == "accept"
            @test b["targetValue"] == 1928        # M(10^8), validated out-of-range
        end

        @testset "REJECT a wrong-but-running candidate" begin
            resp = _call(Dict("code" => _BUGGY, "checkpoints" => cps,
                "cpuSeconds" => 20, "wallSeconds" => 28))
            b = _json(resp)
            @test resp.status == 200
            @test b["status"] == "ok"
            @test b["verdict"] == "reject"
            @test haskey(b, "firstFail")
        end

        @testset "containment — malicious candidate is AST-rejected, never runs" begin
            resp = _call(Dict("code" => "candidate(x) = (open(\"/tmp/ep_pwned\",\"w\"); 0)",
                "checkpoints" => cps))
            b = _json(resp)
            @test b["verdict"] == "na"
            @test b["status"] == "ast_reject"
            @test !isfile("/tmp/ep_pwned")
        end

        @testset "out-of-range checkpoint rejected (400)" begin
            resp = _call(Dict("code" => _CORRECT, "checkpoints" => [JuliaMathSidecar.NT_MAX_N + 1]))
            @test resp.status == 400
            @test _json(resp)["error"] == "bad_request"
        end
    finally
        delete!(ENV, "ET_DISCOVER_EXEC")
    end
end
