"""Number-theoretic predicates and per-window scalar evaluator.

Phase 23.1 substrate. PreparedRange precomputes a smallest-prime-factor
table once so per-window work is roughly linear in window size (the
inner factor lookup is O(log n) but the dominant cost is the per-n loop).

Predicates implemented:
  - is_prime, omega (distinct prime factors), big_omega (with multiplicity)
  - sigma (sum of divisors), divisor_count
  - mobius
  - is_happy (iterated digit-square sums)
"""

from __future__ import annotations


def _smallest_prime_factor(max_n: int) -> list[int]:
    """spf[n] = smallest prime factor of n, for 0 <= n <= max_n."""
    spf = list(range(max_n + 1))
    i = 2
    while i * i <= max_n:
        if spf[i] == i:  # i is prime
            for j in range(i * i, max_n + 1, i):
                if spf[j] == j:
                    spf[j] = i
        i += 1
    return spf


class PreparedRange:
    """Sieve-backed number-theoretic evaluator over [0, max_n]."""

    def __init__(self, max_n: int):
        self.max_n = max_n
        self.spf = _smallest_prime_factor(max_n)

    def _factor(self, n: int) -> list[tuple[int, int]]:
        factors: list[tuple[int, int]] = []
        while n > 1:
            p = self.spf[n]
            e = 0
            while n % p == 0:
                n //= p
                e += 1
            factors.append((p, e))
        return factors

    def is_prime(self, n: int) -> bool:
        return n >= 2 and self.spf[n] == n

    def omega(self, n: int) -> int:
        return len(self._factor(n)) if n >= 2 else 0

    def big_omega(self, n: int) -> int:
        return sum(e for _, e in self._factor(n)) if n >= 2 else 0

    def sigma(self, n: int) -> int:
        """Sum of divisors of n."""
        if n < 2:
            return 1 if n == 1 else 0
        result = 1
        for p, e in self._factor(n):
            result *= (p ** (e + 1) - 1) // (p - 1)
        return result

    def divisor_count(self, n: int) -> int:
        if n < 2:
            return 1 if n == 1 else 0
        result = 1
        for _, e in self._factor(n):
            result *= e + 1
        return result

    def mobius(self, n: int) -> int:
        if n < 2:
            return 1 if n == 1 else 0
        result = 1
        for _, e in self._factor(n):
            if e > 1:
                return 0
            result = -result
        return result


def is_happy(n: int) -> bool:
    """Happy = iterated sum-of-squares-of-digits reaches 1.

    Independent of PreparedRange because the iteration descends below n
    quickly and is cheap; not worth caching for our window sizes.
    """
    if n < 1:
        return False
    seen = set()
    while n != 1 and n not in seen:
        seen.add(n)
        n = sum(int(d) ** 2 for d in str(n))
    return n == 1


def evaluate_window(prep: PreparedRange, start: int, size: int) -> dict:
    """Return per-window scalars for integers in [start, start+size).

    Integers below 2 are excluded (number-theoretic predicates are not
    meaningful on 0 and 1).
    """
    end = start + size
    actual_start = max(2, start)
    actual_size = end - actual_start
    if actual_size <= 0:
        return {}

    n_abundant = n_deficient = 0
    n_prime = n_happy = 0
    sum_omega = sum_big_omega = sum_divisor_count = 0
    max_divisor_count = 0
    mertens_delta = 0

    for n in range(actual_start, end):
        s = prep.sigma(n)
        if s > 2 * n:
            n_abundant += 1
        elif s < 2 * n:
            n_deficient += 1
        # perfect numbers (s == 2n) excluded: too rare per window to matter

        if prep.is_prime(n):
            n_prime += 1
        if is_happy(n):
            n_happy += 1

        sum_omega += prep.omega(n)
        sum_big_omega += prep.big_omega(n)
        d = prep.divisor_count(n)
        sum_divisor_count += d
        if d > max_divisor_count:
            max_divisor_count = d
        mertens_delta += prep.mobius(n)

    inv_size = 1.0 / actual_size
    return {
        "abundant_density":   n_abundant * inv_size,
        "deficient_density":  n_deficient * inv_size,
        "prime_density":      n_prime * inv_size,
        "happy_density":      n_happy * inv_size,
        "mean_omega":         sum_omega * inv_size,
        "mean_Omega":         sum_big_omega * inv_size,
        "mean_divisor_count": sum_divisor_count * inv_size,
        "max_divisor_count":  float(max_divisor_count),
        "mertens_delta":      float(mertens_delta),
    }
