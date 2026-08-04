# =====================================================================
# test/sandbox_runner_test.jl — fork 2b Phase 1 containment tests.
#
# Standalone: includes src/sandbox_runner.jl directly (Base-only), does
# NOT load JuliaMathSidecar, so it triggers no package recompile and the
# live sidecar is unaffected. Run directly:
#   julia julia-math-sidecar/test/sandbox_runner_test.jl
#
# The deliverable is CONTAINMENT (scope §7.3): a hanging / OOM / FS-touch
# / `using` / crashing candidate must be contained — the runner returns a
# non-:ok status and the parent process keeps working — never crashed or
# hung. Plus the moat must ACCEPT a correct candidate and REJECT a
# wrong-but-running one through the sandbox.
# =====================================================================
using Test
include(joinpath(@__DIR__, "..", "src", "sandbox_runner.jl"))
using .SandboxRunner

# Independent oracle (linear Möbius sieve) — trusted side, in-process.
function oracle_upto(maxx::Int)
    mu = Vector{Int8}(undef, maxx); mu[1] = 1; comp = falses(maxx); primes = Int[]
    for i in 2:maxx
        if !comp[i]; push!(primes, i); mu[i] = -1; end
        for p in primes
            ip = i * p; ip > maxx && break; comp[ip] = true
            if i % p == 0; mu[ip] = 0; break; else; mu[ip] = -mu[i]; end
        end
    end
    Mfull = Vector{Int}(undef, maxx); a = 0
    for n in 1:maxx; a += mu[n]; Mfull[n] = a; end
    return Mfull
end

const Mfull  = oracle_upto(1_000_000)
const ORACLE = Dict(k => Mfull[k] for k in (1, 2, 10, 1_000, 100_000, 1_000_000))
const OEIS   = Dict(10_000_000 => 1037)            # A084237, beyond the oracle's range
const INPUTS = sort(collect(union(keys(ORACLE), keys(OEIS))))  # all checkpoints

# Candidate sources (each must define `candidate(x)`).
const CORRECT = """
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

# Same as CORRECT but the leftover-prime sign flip is removed (the classic bug).
const BUGGY = replace(CORRECT, "if rem[i] > 1; mu[i] = -mu[i]; end\n" => "")

const HANG     = "candidate(x) = (while true; end; 0)"
const OOMER     = "candidate(x) = (a = zeros(Int, 10^12); length(a))"
const FS_TOUCH = "candidate(x) = (open(\"/tmp/pwned_by_candidate\", \"w\"); 0)"
const USES     = "using LinearAlgebra\ncandidate(x) = 0"
const CCALLER  = "candidate(x) = Int(ccall(:getpid, Int32, ()))"
const STACK    = "candidate(x) = 1 + candidate(x)"

@testset "fork 2b Phase 1 — sandbox runner" begin

    @testset "AST allowlist" begin
        @test isempty(ast_violations(CORRECT))
        @test "open"  in ast_violations(FS_TOUCH)
        @test "using" in ast_violations(USES)
        @test "ccall" in ast_violations(CCALLER)
        @test !isempty(ast_violations("candidate(x) = unsafe_load(Ptr{Int}(1))"))   # unsafe_ prefix
        @test !isempty(ast_violations("candidate(x) = run(`ls`)"))
        @test ast_violations("candidate(x) = 1 +") == ["parse_error"]   # incomplete expr
    end

    @testset "moat ACCEPTS a correct candidate through the sandbox" begin
        res = run_sandboxed(CORRECT, INPUTS; cpu_seconds=20, wall_seconds=30)
        @test res.status === :ok
        verdict, fail = moat_judge(res, ORACLE, OEIS)
        @test verdict === :accept
        @test res.values[10_000_000] == 1037     # extended beyond oracle range, validated
    end

    @testset "moat REJECTS a wrong-but-running candidate" begin
        res = run_sandboxed(BUGGY, INPUTS; cpu_seconds=20, wall_seconds=30)
        @test res.status === :ok                  # it RAN (didn't crash)...
        verdict, fail = moat_judge(res, ORACLE, OEIS)
        @test verdict === :reject                 # ...but the moat caught it
        @test fail[1] in keys(ORACLE)              # caught at an overlap checkpoint
        @test res.values[2] != ORACLE[2]           # the bug really is present (M(2) wrong)
    end

    @testset "containment — pathological candidates can't escape" begin
        # AST-rejected before any spawn:
        @test run_sandboxed(FS_TOUCH, INPUTS).status === :ast_reject
        @test run_sandboxed(USES,     INPUTS).status === :ast_reject
        @test run_sandboxed(CCALLER,  INPUTS).status === :ast_reject
        @test !isfile("/tmp/pwned_by_candidate")   # the FS-touch never executed

        # Runtime-contained (subprocess killed, parent fine). wall < cpu so the
        # wall `timeout` governs (clean 124) before the CPU limit:
        @test run_sandboxed(HANG, INPUTS; cpu_seconds=10, wall_seconds=5).status === :timeout
        @test run_sandboxed(OOMER, INPUTS; cpu_seconds=10, wall_seconds=20).status in (:oom, :crash, :error)
        @test run_sandboxed(STACK, INPUTS; cpu_seconds=10, wall_seconds=20).status in (:oom, :crash, :error)
    end

    @testset "liveness — runner is reusable after a hang/crash" begin
        # After all the bad candidates above, a correct one still validates:
        res = run_sandboxed(CORRECT, INPUTS; cpu_seconds=20, wall_seconds=30)
        @test res.status === :ok
        @test moat_judge(res, ORACLE, OEIS)[1] === :accept
    end
end
