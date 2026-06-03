# =====================================================================
# numbertheory.jl — number-theoretic predicates + window aggregator
# =====================================================================
#
# Port of the JVM `MathConjectureSubstrate` (phase 28 §28.1). Exposes a
# smallest-prime-factor (SPF) sieve + the same nine per-window scalars
# the JVM substrate computes, so the conjecture mining + verification
# pipeline can delegate compute to the sidecar.
#
# Why a module-level sieve
# ------------------------
# The SPF table for n ≤ 2_000_000 is ~8 MB (Int32-sized). It builds in
# ~100 ms single-threaded. Building it on every /numbertheory/window call
# would dominate the request latency; caching it process-wide is the right
# move. Concurrent requests share the read-only table after the one-time
# build.
#
# Lazy build
# ----------
# The table is built on first call rather than at module load so that:
#   - module load stays fast (the test runner constructs the module
#     several times in dependency probing);
#   - a process that never hits the conjecture endpoints doesn't pay
#     the memory cost.
#
# Parity with the JVM
# -------------------
# Output field names, ordering, and numeric semantics match
# `MathConjectureSubstrate.evaluateWindow` byte-for-byte. The Java
# `LinkedHashMap` returns keys in insertion order; the Julia
# `OrderedDict` (via JSON3 → JSON object) does the same. The parity
# check that ships with §28.2 confirms equality across a fixed window
# corpus.
# =====================================================================

using JSON3
using Base.Threads: ReentrantLock

# Sieve ceiling. Covers `ConjectureVerifier.MAX_N` (2_000_000) in full —
# raised from 1_050_000 in phase-28 §28.6 (conjecture legacy removal) so the
# sidecar is the SOLE number-theory-window compute path: the JVM's in-process
# MathConjectureSubstrate (which previously covered the 1.05M–2M range via
# fallback) was deleted, so the sidecar must serve the verifier's whole range.
const NT_MAX_N = 2_000_000

# Module-state holders. `Ref` so we can lazily set them under a lock
# without making the sieve a `const` (which would force build-at-load).
const NT_SPF      = Ref{Vector{Int32}}()
const NT_BUILT    = Ref{Bool}(false)
const NT_BUILD_LOCK = ReentrantLock()

"""
    nt_ensure_sieve!()

Build the SPF sieve on first call. Concurrent callers race the lock
but only the first wins the build; subsequent callers see
`NT_BUILT[] == true` on the fast path.
"""
function nt_ensure_sieve!()
    NT_BUILT[] && return nothing
    lock(NT_BUILD_LOCK) do
        NT_BUILT[] && return nothing
        spf = Vector{Int32}(undef, NT_MAX_N + 1)
        @inbounds for i in 0:NT_MAX_N
            spf[i + 1] = Int32(i)
        end
        # Standard linear-sieve variant used by the JVM port.
        i = 2
        @inbounds while Int64(i) * i <= NT_MAX_N
            if spf[i + 1] == i
                j = i * i
                while j <= NT_MAX_N
                    if spf[j + 1] == j
                        spf[j + 1] = Int32(i)
                    end
                    j += i
                end
            end
            i += 1
        end
        NT_SPF[] = spf
        NT_BUILT[] = true
    end
    return nothing
end

"""
    nt_factor(n) -> Vector{Tuple{Int,Int}}

Prime factorization of `n` as a list of `(prime, exponent)` tuples,
using the cached SPF sieve. Returns an empty vector for `n < 2`.
"""
function nt_factor(n::Integer)
    n < 2 && return Tuple{Int,Int}[]
    n > NT_MAX_N && throw(ArgumentError("n=$n exceeds NT_MAX_N=$NT_MAX_N"))
    nt_ensure_sieve!()
    spf = NT_SPF[]
    out = Tuple{Int,Int}[]
    m = Int(n)
    while m > 1
        p = Int(spf[m + 1])
        e = 0
        while m % p == 0
            m ÷= p
            e += 1
        end
        push!(out, (p, e))
    end
    return out
end

nt_is_prime(n::Integer) = n >= 2 && n <= NT_MAX_N &&
                          (nt_ensure_sieve!(); NT_SPF[][n + 1] == n)

"""
    nt_prime_indicator(n) -> Int

1 if `n` is prime, else 0 — the per-n term whose cumulative sum is the prime-counting
function π(x). Lets the discovery oracle escalate π(x) the same way it does M(x) / D(x):
a trusted per-n Int function the cumulative pass sums (see `handle_discover_validate`).
"""
nt_prime_indicator(n::Integer)::Int = nt_is_prime(n) ? 1 : 0

function nt_omega(n::Integer)
    n < 2 && return 0
    return length(nt_factor(n))
end

function nt_big_omega(n::Integer)
    n < 2 && return 0
    s = 0
    for (_, e) in nt_factor(n)
        s += e
    end
    return s
end

"""
    nt_sigma(n) -> Int64

Sum of divisors of `n`. Returns 0 for `n < 1`, 1 for `n == 1`.
"""
function nt_sigma(n::Integer)
    n < 1 && return Int64(0)
    n == 1 && return Int64(1)
    result = Int64(1)
    for (p, e) in nt_factor(n)
        pe1 = Int64(1)
        for _ in 0:e
            pe1 *= p
        end
        result *= (pe1 - 1) ÷ (p - 1)
    end
    return result
end

function nt_divisor_count(n::Integer)
    n < 1 && return 0
    n == 1 && return 1
    result = 1
    for (_, e) in nt_factor(n)
        result *= (e + 1)
    end
    return result
end

function nt_mobius(n::Integer)
    n < 1 && return 0
    n == 1 && return 1
    result = 1
    for (_, e) in nt_factor(n)
        e > 1 && return 0
        result = -result
    end
    return result
end

"""
    nt_is_happy(n) -> Bool

Happy-number predicate. Mirrors the JVM substrate: a number is happy
if iterating `n → sum-of-digit-squares` reaches 1; iterating it cycles
otherwise. Bounded at 100 iterations as a safety net.
"""
function nt_is_happy(n::Integer)
    n < 1 && return false
    seen0 = -1
    seen1 = -1
    seen2 = -1
    cur = Int(n)
    for _ in 1:100
        cur == 1 && return true
        (cur == seen0 || cur == seen1 || cur == seen2) && return false
        seen2 = seen1
        seen1 = seen0
        seen0 = cur
        s = 0
        x = cur
        while x > 0
            d = x % 10
            s += d * d
            x ÷= 10
        end
        cur = s
    end
    return false
end

"""
    nt_evaluate_window(start, size) -> Vector{Pair{String,Float64}}

Per-window aggregate of nine scalars over `[start, start+size)`.
Integers below 2 are excluded (the JVM substrate skips them too).

Returns a `Vector{Pair}` rather than a `Dict` to preserve insertion
order on JSON serialisation — the JVM `LinkedHashMap` parity check in
§28.2 compares ordered key sequences.

Throws `ArgumentError` if `start + size > NT_MAX_N + 1`.
"""
function nt_evaluate_window(start::Integer, size::Integer)
    end_ = start + size
    if end_ > NT_MAX_N + 1
        throw(ArgumentError(
            "window [$start, $end_) exceeds NT_MAX_N=$NT_MAX_N"))
    end
    nt_ensure_sieve!()

    actual_start = max(2, Int(start))
    actual_size = Int(end_) - actual_start

    if actual_size <= 0
        # Match the JVM: empty window → empty map. Caller decides how to
        # represent that on the wire; we return an empty Vector.
        return Pair{String,Float64}[]
    end

    n_abundant = 0
    n_deficient = 0
    n_prime = 0
    n_happy = 0
    sum_omega = Int64(0)
    sum_big_omega = Int64(0)
    sum_divisor_count = Int64(0)
    max_divisor_count = 0
    mertens_delta = 0

    @inbounds for n in actual_start:(Int(end_) - 1)
        s = nt_sigma(n)
        if s > 2 * Int64(n)
            n_abundant += 1
        elseif s < 2 * Int64(n)
            n_deficient += 1
        end
        # n == perfect (s == 2n) intentionally excluded per JVM substrate.

        nt_is_prime(n) && (n_prime += 1)
        nt_is_happy(n) && (n_happy += 1)

        sum_omega += nt_omega(n)
        sum_big_omega += nt_big_omega(n)
        d = nt_divisor_count(n)
        sum_divisor_count += d
        d > max_divisor_count && (max_divisor_count = d)
        mertens_delta += nt_mobius(n)
    end

    inv = 1.0 / actual_size
    return Pair{String,Float64}[
        "abundant_density"   => n_abundant * inv,
        "deficient_density"  => n_deficient * inv,
        "prime_density"      => n_prime * inv,
        "happy_density"      => n_happy * inv,
        "mean_omega"         => sum_omega * inv,
        "mean_Omega"         => sum_big_omega * inv,
        "mean_divisor_count" => sum_divisor_count * inv,
        "max_divisor_count"  => Float64(max_divisor_count),
        "mertens_delta"      => Float64(mertens_delta),
    ]
end

# =====================================================================
# Phase 30 — cumulative Mertens function M(x) beyond NT_MAX_N
# =====================================================================
# Spike: phases-for-human-like-performance/phase-30-conjecture/
#        segmented-mertens-spike.md
#
# Two paths to M(x) = Σ_{n=1}^{x} μ(n):
#   - nt_mertens_dense(x)      — trusted ground truth; reuses the dense
#       SPF sieve via nt_mobius. Capped at NT_MAX_N (the dense array).
#   - nt_mertens_segmented(x)  — a segmented Möbius sieve that needs only
#       primes ≤ √x (drawn from the same SPF sieve), so x may exceed
#       NT_MAX_N up to NT_MAX_N². Linear in x.
#
# The two MUST agree exactly on the overlap range [0, NT_MAX_N] — that
# equality is the trust gate (the Java SegmentedMertensValidator checks
# it before any out-of-range value is believed). |M(x)| ~ √x, so values
# stay tiny and Int64 is safe.
# =====================================================================

"""
    nt_mertens_dense(x) -> Int64

Cumulative Mertens function `M(x) = Σ_{n=1}^{x} μ(n)`, summed directly
over the dense SPF sieve (the trusted reference). Returns 0 for `x < 1`.
Throws `ArgumentError` for `x > NT_MAX_N` — use `nt_mertens_segmented`
beyond the dense array.
"""
function nt_mertens_dense(x::Integer)
    x < 1 && return Int64(0)
    x > NT_MAX_N && throw(ArgumentError(
        "x=$x exceeds NT_MAX_N=$NT_MAX_N; use nt_mertens_segmented"))
    nt_ensure_sieve!()
    total = Int64(0)
    @inbounds for n in 1:Int(x)
        total += nt_mobius(n)
    end
    return total
end

"""
    nt_mertens_segmented(x; block=1_000_000) -> Int64

⚠️ PHASE-1 THROWAWAY BASELINE. This body is hand-written *only* to prove the
moat (overlap-equality against `nt_mertens_dense`) end-to-end. It is NOT the
intended source of the method: per `segmented-mertens-spike.md` fork #2, clever
`M(x)` methods are meant to be **LLM-proposed and gated by this same moat at
runtime**, not authored here. Treat this as a stand-in candidate until the
runtime self-verifying path lands.

Cumulative Mertens function `M(x) = Σ_{n=1}^{x} μ(n)` via a segmented
Möbius sieve. Sieves `μ` over blocks `[lo, hi]` using only primes
`≤ √x` (drawn from the SPF sieve), so the dense `NT_MAX_N` array is not
needed: `x` may range up to `NT_MAX_N²` (primes ≤ √x must exist in the
sieve). Linear in `x`; the caller enforces the work guard. Returns 0 for
`x < 1`. Throws `ArgumentError` if `√x` exceeds `NT_MAX_N`.

Method: init `μ = +1`, track the unfactored cofactor `rem = n`. For each
prime `p ≤ √x`: flip `μ` and divide `rem` by `p` on multiples of `p`,
zero `μ` on multiples of `p²` (square factor). After all such primes, any
`rem > 1` is a single leftover prime `> √x`, so flip `μ` once more.
"""
function nt_mertens_segmented(x::Integer; block::Integer = 1_000_000)
    x < 1 && return Int64(0)
    xx = Int64(x)
    r = isqrt(xx)
    r > NT_MAX_N && throw(ArgumentError(
        "x=$xx needs primes up to √x=$r which exceeds NT_MAX_N=$NT_MAX_N"))
    nt_ensure_sieve!()
    spf = NT_SPF[]

    # Primes ≤ √x, pulled from the SPF sieve (spf[p+1] == p ⇔ p prime).
    primes = Int[]
    @inbounds for p in 2:Int(r)
        spf[p + 1] == p && push!(primes, p)
    end

    total = Int64(0)
    blk = Int64(block)
    lo = Int64(1)
    while lo <= xx
        hi = min(lo + blk - 1, xx)
        len = Int(hi - lo + 1)
        mu = ones(Int8, len)
        rem = Vector{Int64}(undef, len)
        @inbounds for i in 1:len
            rem[i] = lo + (i - 1)
        end
        for p in primes
            pl = Int64(p)
            pl > hi && break              # no multiple of p in this block (primes ascending)
            # multiples of p: flip sign, peel one factor of p
            j = cld(lo, pl) * pl
            @inbounds while j <= hi
                idx = Int(j - lo) + 1
                mu[idx] = -mu[idx]
                rem[idx] ÷= pl
                j += pl
            end
            # multiples of p²: not squarefree → μ = 0
            pp = pl * pl
            if pp <= hi
                j2 = cld(lo, pp) * pp
                @inbounds while j2 <= hi
                    mu[Int(j2 - lo) + 1] = 0
                    j2 += pp
                end
            end
        end
        @inbounds for i in 1:len
            (mu[i] != 0 && rem[i] > 1) && (mu[i] = -mu[i])   # leftover prime > √x
            total += mu[i]
        end
        lo = hi + 1
    end
    return total
end
