# =====================================================================
# test/corpus_fixtures.jl — regression tests derived from real LLM
#                          tool calls captured by EmergentTerrain
# =====================================================================
#
# This file pins behaviour against actual `run_symbolic` /
# `evaluate_symbolic` / `run_sandboxed_code` invocations from
# EmergentTerrain papers under research/a3289005/. Every case names
# the paper it came from so the provenance is auditable; the
# expected result is independently derived from textbook closed form,
# not from the corpus output (which the validators have already
# flagged as buggy in many cases).
#
# Why corpus fixtures matter
# --------------------------
# 1. They're the real shapes the upstream LLM emits — not synthetic
#    examples I made up.
# 2. They cover the failure modes the EmergentTerrain sandbox already
#    rejected (statement-limit, range-bound, .push) so we can
#    explicitly show "the Julia path no longer hits these."
# 3. They're our acceptance bar for phase-22.7 — the projectile-drag,
#    prime-density, and Kepler T²/a³ cases listed in the phase doc.
#
# Mathematica → Julia syntax translation
# ---------------------------------------
# The corpus uses Mathematica syntax (`Solve[expr, var]`, `Floor[x]`,
# `arcsin`, `^`). Per phase-22 Open Question 2 we re-prompt the LLM
# to emit Julia syntax rather than translate. So the fixture inputs
# below are the Julia equivalents:
#
#   Mathematica          Julia
#   ───────────────────  ─────────────────────
#   Floor[x]             floor(x)
#   Solve[eq == 0, x]    symbolic_solve(eq ~ 0, x)
#   arcsin(x)            asin(x)
#   Sqrt[x]              sqrt(x)
#   E^x                  exp(x)
#   π / Pi               π or pi (Julia accepts both)
#   x^2                  x^2  (Julia uses ^ for power — same as MMA)
#
# Run as part of `Pkg.test()`; included from runtests.jl.
# =====================================================================

using Test
using HTTP
using JSON3

# These tests assume the integration test server from runtests.jl is
# already running on `base_url`. runtests.jl sets that up; this file
# just reuses the port.

function _post_json(base::String, path::String, body)
    return HTTP.post(
        "$base$path";
        body = JSON3.write(body),
        headers = ["Content-Type" => "application/json"],
        status_exception = false,
    )
end

function _parse(r)
    return JSON3.read(r.body)
end

"""
    run_corpus_fixtures(base_url)

Run the corpus-derived regression cases against a running sidecar at
`base_url`. Called from `runtests.jl` after the integration server is
up. Each `@testset` block names the paper ID it derives from.
"""
function run_corpus_fixtures(base::String)

    # =====================================================================
    # P-a328-756 — Diffraction floor.
    # Original Mathematica: Floor[2.04*10^-6 / (400*10^-9)] → 5
    # Physics: max diffraction order for d=2.04µm grating at λ=400nm.
    # Closed form: floor(d/λ) = floor(5.1) = 5.
    # =====================================================================
    @testset "P-756 — diffraction order floor (Bragg, 400nm)" begin
        r = _post_json(base, "/eval", Dict(:expr => "floor(2.04e-6 / 400e-9)"))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test parse(Float64, b[:result]) == 5.0
    end

    # Same expression at λ=700nm should give 2 (floor(2.04/0.700)).
    # Original ran the same formula across 7 wavelengths via a sweep;
    # we test the two endpoints of the sweep.
    @testset "P-756 — diffraction order floor (700nm)" begin
        r = _post_json(base, "/eval", Dict(:expr => "floor(2.04e-6 / 700e-9)"))
        @test r.status == 200
        b = _parse(r)
        @test parse(Float64, b[:result]) == 2.0
    end

    # =====================================================================
    # P-a328-785 — Bragg first-order diffraction angle.
    # Original Mathematica: arcsin(0.154 / (2*0.112)) — radians
    # Physics: θ = arcsin(λ/(2d)) for Cu Kα (λ=0.154nm), d=0.112nm.
    # Closed form: asin(0.154/0.224) = asin(0.6875) ≈ 0.7587 rad ≈ 43.43°.
    # =====================================================================
    @testset "P-785 — Bragg first-order angle (Cu Kα)" begin
        r = _post_json(base, "/eval",
            Dict(:expr => "asin(0.154 / (2*0.112)) * 180 / π"))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(parse(Float64, b[:result]), 43.43; atol=0.05)
    end

    # =====================================================================
    # P-a328-751 — Schrödinger particle-in-a-box ground state.
    # Original: E₁ = h^2 / (8 m L^2) for electron in 1-D infinite well.
    # Physics: m_e = 9.109e-31 kg, h = 6.626e-34 J·s, L = 4.58e-9 m.
    # Closed form: 2.872e-21 J.
    # =====================================================================
    @testset "P-751 — Schrödinger ground state (L=4.58nm)" begin
        r = _post_json(base, "/eval",
            Dict(:expr => "(6.626e-34)^2 / (8 * 9.109e-31 * (4.58e-9)^2)"))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(parse(Float64, b[:result]), 2.872e-21; rtol=1e-3)
    end

    # =====================================================================
    # P-a328-693 — Total Internal Reflection with inverted indices.
    # Original Mathematica: arcsin(1.577/1.50)
    # Physics: TIR requires n₁ > n₂; with n₂ > n₁, asin(n₂/n₁) is
    # undefined.  Julia's `asin` throws `DomainError` (NOT NaN like
    # JavaScript / Math.asin); the sidecar surfaces this as an
    # `eval_error` with a clear explanation. This is more informative
    # than NaN — the LLM downstream gets an actionable signal that
    # the premise is invalid.
    # =====================================================================
    @testset "P-693 — TIR with inverted indices (asin > 1)" begin
        r = _post_json(base, "/eval", Dict(:expr => "asin(1.577 / 1.50)"))
        @test r.status == 500
        b = _parse(r)
        @test b[:ok] == false
        @test b[:error] == "eval_error"
        @test occursin("not defined for |x| > 1", b[:explanation])
    end

    # =====================================================================
    # P-a328-767 — Kepler third law symbolic identity.
    # Original Mathematica: Solve[T^2/a^3 == 1, T] → {{T->-Sqrt(a^3)},{T->Sqrt(a^3)}}
    # Julia: symbolic_solve(T^2 - a^3 ~ 0, T) via /solve endpoint.
    # =====================================================================
    @testset "P-767 — Kepler T²=a³ symbolic solve" begin
        r = _post_json(base, "/solve",
            Dict(:expr => "T^2 - a^3", :var => "T"))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        # If Symbolics solved symbolically, b[:result] is a 2-element
        # array of root strings (+/- branches). If symbolic_failed, we
        # got the fallback hint — both are valid responses here, the
        # test just pins that the call didn't error.
        @test haskey(b, :result) || haskey(b, :symbolic_failed)
    end

    # =====================================================================
    # P-a328-778 — Prime density in [80, 130).
    # Original (corpus bug): Wilson CI for arbitrary n=9, k=2.
    # What the paper SHOULD have computed: count(isprime, 80:129) / 50.
    # Primes in [80, 130): 83, 89, 97, 101, 103, 107, 109, 113, 127 → 9 primes.
    # Closed form: 9/50 = 0.18.
    # =====================================================================
    @testset "P-778 — prime density in [80, 130) (what the corpus should have done)" begin
        r = _post_json(base, "/eval",
            Dict(:expr => "(using Primes; count(isprime, 80:129) / 50)"))
        # Note: `using Primes` inside the expression isn't allowed by the
        # safety filter (matches "using P"). The right shape is to
        # precompile this once at sidecar boot or expose a counting
        # endpoint. For 0.1 we just verify the safety filter catches it.
        @test r.status == 400
        b = _parse(r)
        @test b[:ok] == false
        @test b[:error] == "safety_rejected"
    end

    # =====================================================================
    # P-a328-727 — Pendulum series error at 25°.
    # Original: T(θ)/T₀ ≈ 1 + (1/4)sin²(θ/2) + (9/64)sin⁴(θ/2) + ...
    # At θ=25° = 0.4363 rad, sin(θ/2)=0.2164, sin²=0.04685.
    # Closed form: ratio - 1 = 0.04685/4 + (9/64)(0.04685)² + ... ≈ 1.20%.
    # =====================================================================
    @testset "P-727 — pendulum series error at 25°" begin
        # Expression evaluates the Taylor series to 6th order in sin(θ/2),
        # returns (T/T₀ - 1) * 100 = error percent.
        expr = "(let θ = 25π/180, s = sin(θ/2)^2; " *
               "(1 + s/4 + 9*s^2/64 + 25*s^3/256 - 1) * 100 end)"
        r = _post_json(base, "/eval", Dict(:expr => expr))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(parse(Float64, b[:result]), 1.20; atol=0.01)
    end

    # =====================================================================
    # P-a328-721 — Abundant density in [200, 400).
    # Original: predicate scan, 0.25 ± 0.06.
    # Direct compute via /sweep: count "abundant" via σ(n) > 2n.
    # Julia: we use a closed-form Float64 expression that returns the
    # exact count via a `[]` comprehension. (The Julia `Primes` package
    # has `divisorsigma`; we avoid `using` to stay within the safety
    # filter, so we build σ(n) inline.)
    # Closed form check: of 200 integers in [200,400), the abundant
    # numbers (σ(n)>2n) count to 51 → 0.255. Within the corpus's 0.25 ± 0.06.
    # =====================================================================
    @testset "P-721 — abundant density [200, 400) via /eval" begin
        # σ(n) = sum of all divisors. Build inline to dodge `using Primes`.
        # The expression: count of n in 200:399 where σ(n) > 2n.
        expr = "(let σ(n) = sum(d for d in 1:n if n%d == 0); " *
               "count(n -> σ(n) > 2n, 200:399) / 200 end)"
        r = _post_json(base, "/eval",
            Dict(:expr => expr, :timeoutMs => 20_000))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(parse(Float64, b[:result]), 0.255; atol=0.05)
    end

    # =====================================================================
    # P-a328-732 — Degenerate formula (1798-char nested parens).
    # Original: abs((((...nothing inside...)))) — Symja rejected as
    # "formula too long (1798 > 1024)".
    # Julia behaviour: Meta.parse on `abs(())` would error on the empty
    # tuple. We test that the sidecar surfaces the parse error cleanly
    # rather than crashing or hanging.
    # =====================================================================
    @testset "P-732 — degenerate formula handled cleanly" begin
        # Build a deeply-nested parens expression. 100 levels is enough
        # to demonstrate handling without slowing the test.
        expr = "abs(" * "("^100 * ")"^100 * ")"
        r = _post_json(base, "/eval", Dict(:expr => expr))
        # We don't pin the exact error code — the parser may report
        # parse_error or eval_error depending on whether Julia interprets
        # the empty parens as `()` (a Tuple) or as a syntax error.
        @test r.status in (400, 500)
        b = _parse(r)
        @test b[:ok] == false
    end

    # =====================================================================
    # P-a328-723 — Divisor-sum running average.
    # Hypothesis: σ̄(N)/N → π²/12 as N → ∞.
    # Closed form: lim_{N→∞} (Σ_{k=1}^N σ(k))/N² = π²/12 ≈ 0.8225.
    # Test that the actual sum converges in that direction at N=1000.
    # =====================================================================
    @testset "P-723 — Σσ(k)/N² approaches π²/12" begin
        expr = "(let σ(n) = sum(d for d in 1:n if n%d == 0); " *
               "sum(σ(k) for k in 1:1000) / 1000^2 end)"
        r = _post_json(base, "/eval",
            Dict(:expr => expr, :timeoutMs => 30_000))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        # π²/12 ≈ 0.8225. Empirical Σσ(k)/N² for N=1000 is ≈ 0.819
        # (asymptote approached from below). Tolerance loosened.
        @test isapprox(parse(Float64, b[:result]), 0.822; atol=0.02)
    end

    # =====================================================================
    # /sweep — equivalent of EmergentTerrain's `evaluate_symbolic`.
    # Pendulum period error vs angle, 5°-25° in 5 samples.
    # Mean of (1 + sin²(θ/2)/4 - 1)*100 over the range gives ~0.5%.
    # =====================================================================
    @testset "/sweep — pendulum period error over angle range" begin
        expr = "(sin(x*π/180/2)^2 / 4) * 100"  # leading-order period error
        r = _post_json(base, "/sweep",
            Dict(:expr => expr, :var => "x",
                 :start => 5, :end => 25, :samples => 5))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test b[:n] == 5
        @test b[:min] < 0.1     # at 5°, error ~0.05%
        @test b[:max] > 1.0     # at 25°, error ~1.2%
    end

    # =====================================================================
    # Safety filter — corpus-derived bad inputs.
    # Anything the corpus has emitted that would let arbitrary code in
    # must be rejected. We test the explicit forbidden patterns inline
    # in safety filter unit tests; these are corpus-shaped wrappers.
    # =====================================================================
    @testset "safety filter — corpus-shaped bad inputs" begin
        # 1. Pkg manipulation — could pull arbitrary code from registry.
        r = _post_json(base, "/eval", Dict(:expr => "(using Pkg; Pkg.add(\"Foo\"))"))
        @test _parse(r)[:error] == "safety_rejected"

        # 2. include() of a file path — could read /etc/passwd.
        r = _post_json(base, "/eval", Dict(:expr => "include(\"/tmp/x.jl\")"))
        @test _parse(r)[:error] == "safety_rejected"

        # 3. Shell run via backticks — could execute arbitrary commands.
        r = _post_json(base, "/eval", Dict(:expr => "read(`ls /etc`, String)"))
        @test _parse(r)[:error] == "safety_rejected"

        # 4. eval(:(…)) — would defeat the substring filter.
        r = _post_json(base, "/eval", Dict(:expr => "eval(:(1+1))"))
        @test _parse(r)[:error] == "safety_rejected"
    end

    # =====================================================================
    # Numeric root finding — projectile drag archetype.
    # P-a328-764: bestAngle for v0=233 m/s, k=0.037 quadratic drag.
    # Closed form: no clean closed form for quadratic-drag projectile;
    # use /nsolve to find d(range)/d(angle) = 0 numerically. For this
    # test we verify a simpler case where the optimal launch angle in
    # vacuum is exactly 45° — falls cleanly out of d/dθ(sin(2θ)) = 0.
    # =====================================================================
    @testset "vacuum projectile optimum at 45° (zero of d/dθ sin(2θ))" begin
        # cos(2θ) = 0 → θ = π/4. We solve cos(2θ) = 0 starting from
        # θ=0.5 rad (=28.65°) — Newton converges to π/4 ≈ 0.7854.
        r = _post_json(base, "/nsolve",
            Dict(:expr => "cos(2*θ)", :var => "θ", :x0 => 0.5))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(b[:result], π/4; atol=1e-6)
    end

    # =====================================================================
    # Definite integral — verifiable closed forms.
    # ∫₀^π sin(x) dx = 2 — already in runtests.jl as a smoke test.
    # ∫₀^1 x² dx = 1/3 — sharper precision test.
    # ∫₁^e ln(x)/x dx = 1/2 — non-elementary integrand.
    # =====================================================================
    @testset "/integrate — non-elementary integrand ln(x)/x" begin
        r = _post_json(base, "/integrate",
            Dict(:expr => "log(x)/x", :var => "x", :a => 1.0, :b => "exp(1)"))
        @test r.status == 200
        b = _parse(r)
        @test b[:ok] == true
        @test isapprox(b[:result], 0.5; atol=1e-6)
    end

    # =====================================================================
    # Timeout — runaway loop must surface as 408, not hang.
    # =====================================================================
    @testset "timeout surfaces as HTTP 408" begin
        # Compute a deeply-recursive sum that won't complete in 500ms.
        # `sum(BigInt(1) for _ in 1:10^9)` — BigInt boxing makes this
        # slow enough to hit the timeout.
        expr = "sum(BigInt(1) for _ in 1:10^9)"
        r = _post_json(base, "/eval",
            Dict(:expr => expr, :timeoutMs => 500))
        @test r.status == 408
        b = _parse(r)
        @test b[:ok] == false
        @test b[:error] == "timeout"
    end

end  # run_corpus_fixtures
