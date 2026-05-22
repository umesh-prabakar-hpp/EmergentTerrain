# =====================================================================
# src/physics_primitives.jl — Phase 28 §28.5a — math/physics primitives
# =====================================================================
#
# Byte-identical ports of the math primitives in
# com.emergentterrain.research.MathPhysicsEngine that
# DynamicComputeEngine uses (and that any future /physics/topic port
# will reuse). Naming matches Java: isPrime, isHappy, collatzSteps,
# sigmaSum, isAbundant, isPerfect. Constants and step caps must stay
# in lockstep with the JVM impl.

"""
    is_prime(n) -> Bool

Trial-division primality with mod-6 wheel — same algorithm as JVM
MathPhysicsEngine.isPrime, including the n < 2 / n < 4 base cases.
"""
function is_prime(n::Integer)::Bool
    n < 2 && return false
    n < 4 && return true
    (n % 2 == 0 || n % 3 == 0) && return false
    i = 5
    while Int64(i) * Int64(i) <= n
        if n % i == 0 || n % (i + 2) == 0
            return false
        end
        i += 6
    end
    return true
end

"""
    is_happy(n) -> Bool

Sum-of-squared-digits iteration. Terminates at 1 (happy) or 4 (cycle
attractor for unhappy numbers); 1000-step cap matches JVM.
"""
function is_happy(n::Integer)::Bool
    x = Int64(n)
    steps = 0
    while x != 1 && x != 4 && steps < 1000
        sum_val = Int64(0)
        while x > 0
            d = x % 10
            sum_val += d * d
            x = x ÷ 10
        end
        x = sum_val
        steps += 1
    end
    return x == 1
end

"""
    collatz_steps(n) -> Int

Steps to reach 1 in the Collatz iteration. Caps at 10_000 to match
JVM (so very-long divergent paths return 10_000 rather than hang).
Returns 0 for n == 1 (matches JVM's `x = max(1, n); steps = 0`).
"""
function collatz_steps(n::Integer)::Int
    x = Int64(max(1, n))
    steps = 0
    while x != 1 && steps < 10_000
        x = x % 2 == 0 ? x ÷ 2 : 3 * x + 1
        steps += 1
    end
    return steps
end

"""
    sigma_sum(n) -> Int64

Sum of proper divisors. JVM treats n <= 1 as 0; same here. Loop
mirrors the JVM trial-divisor sweep — `sigma = 1` (1 is always a
proper divisor) then for d in 2..sqrt(n): add d, and add n÷d when
distinct.
"""
function sigma_sum(n::Integer)::Int64
    n <= 1 && return Int64(0)
    sigma = Int64(1)
    d = Int64(2)
    while d * d <= n
        if n % d == 0
            sigma += d
            if d != n ÷ d
                sigma += n ÷ d
            end
        end
        d += 1
    end
    return sigma
end

"""
    is_abundant(n) -> Bool

n is abundant iff sigma_sum(n) > n. Predicate form for /predicate/scan.
"""
is_abundant(n::Integer) = sigma_sum(n) > n

"""
    is_perfect(n) -> Bool

n is perfect iff sigma_sum(n) == n. Predicate form for /predicate/scan.
"""
is_perfect(n::Integer) = sigma_sum(n) == n
