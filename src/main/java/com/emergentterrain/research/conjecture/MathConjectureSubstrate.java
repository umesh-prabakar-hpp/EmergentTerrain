package com.emergentterrain.research.conjecture;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Number-theoretic predicates and per-window scalar evaluator. Mirrors
 * {@code scripts/conjecture-miner/math/substrate.py}.
 *
 * <p>A {@link MathConjectureSubstrate} instance precomputes a
 * smallest-prime-factor sieve up to {@code maxN}, so per-window work is
 * roughly linear in window size.
 *
 * <p>Predicates: {@link #isPrime}, {@link #omega} (distinct primes),
 * {@link #bigOmega} (with multiplicity), {@link #sigma} (sum of divisors),
 * {@link #divisorCount}, {@link #mobius}. {@link #isHappy} is a free
 * function — the iteration converges below n quickly and isn't worth
 * sieve-caching at window-mining scales.
 */
public final class MathConjectureSubstrate {

    private final int[] spf;
    private final int maxN;

    public MathConjectureSubstrate(int maxN) {
        if (maxN < 2) {
            throw new IllegalArgumentException("maxN must be >= 2");
        }
        this.maxN = maxN;
        this.spf = smallestPrimeFactorTable(maxN);
    }

    public int maxN() {
        return maxN;
    }

    private static int[] smallestPrimeFactorTable(int maxN) {
        int[] spf = new int[maxN + 1];
        for (int i = 0; i <= maxN; i++) {
            spf[i] = i;
        }
        for (int i = 2; (long) i * i <= maxN; i++) {
            if (spf[i] == i) {
                for (int j = i * i; j <= maxN; j += i) {
                    if (spf[j] == j) {
                        spf[j] = i;
                    }
                }
            }
        }
        return spf;
    }

    /** Returns the prime factorization as a list of (prime, exponent) pairs, encoded as a flat int[2*k] array. */
    private int[] factorPacked(int n) {
        if (n < 2) {
            return new int[0];
        }
        int[] tmp = new int[40];  // log2(maxN) bound; 40 covers up to ~10^12
        int k = 0;
        while (n > 1) {
            int p = spf[n];
            int e = 0;
            while (n % p == 0) {
                n /= p;
                e++;
            }
            tmp[k++] = p;
            tmp[k++] = e;
        }
        int[] out = new int[k];
        System.arraycopy(tmp, 0, out, 0, k);
        return out;
    }

    public boolean isPrime(int n) {
        return n >= 2 && spf[n] == n;
    }

    public int omega(int n) {
        if (n < 2) return 0;
        return factorPacked(n).length / 2;
    }

    public int bigOmega(int n) {
        if (n < 2) return 0;
        int[] f = factorPacked(n);
        int s = 0;
        for (int i = 1; i < f.length; i += 2) {
            s += f[i];
        }
        return s;
    }

    /** Sum of divisors of n. */
    public long sigma(int n) {
        if (n < 2) return n == 1 ? 1 : 0;
        int[] f = factorPacked(n);
        long result = 1;
        for (int i = 0; i < f.length; i += 2) {
            int p = f[i];
            int e = f[i + 1];
            long pe1 = 1;
            for (int x = 0; x <= e; x++) {
                pe1 *= p;
            }
            result *= (pe1 - 1) / (p - 1);
        }
        return result;
    }

    public int divisorCount(int n) {
        if (n < 2) return n == 1 ? 1 : 0;
        int[] f = factorPacked(n);
        int result = 1;
        for (int i = 1; i < f.length; i += 2) {
            result *= (f[i] + 1);
        }
        return result;
    }

    public int mobius(int n) {
        if (n < 2) return n == 1 ? 1 : 0;
        int[] f = factorPacked(n);
        int result = 1;
        for (int i = 1; i < f.length; i += 2) {
            if (f[i] > 1) return 0;
            result = -result;
        }
        return result;
    }

    public static boolean isHappy(int n) {
        if (n < 1) return false;
        // Bounded iteration: every starting n eventually hits 1 or the
        // 8-cycle {4, 16, 37, 58, 89, 145, 42, 20}. Cap iterations as a
        // safety net.
        int seen0 = 0, seen1 = 0, seen2 = 0;
        int cur = n;
        for (int iter = 0; iter < 100; iter++) {
            if (cur == 1) return true;
            if (cur == seen0 || cur == seen1 || cur == seen2) return false;
            seen2 = seen1;
            seen1 = seen0;
            seen0 = cur;
            int s = 0;
            int x = cur;
            while (x > 0) {
                int d = x % 10;
                s += d * d;
                x /= 10;
            }
            cur = s;
        }
        return false;
    }

    /**
     * Evaluate per-window scalars for integers in [{@code start},
     * {@code start+size}). Integers below 2 are excluded.
     *
     * <p>Returned map keys match {@code substrate.py:evaluate_window}.
     */
    public Map<String, Double> evaluateWindow(int start, int size) {
        int end = start + size;
        if (end > maxN + 1) {
            throw new IllegalArgumentException(
                    "window [" + start + "," + end + ") exceeds maxN=" + maxN);
        }
        int actualStart = Math.max(2, start);
        int actualSize = end - actualStart;
        Map<String, Double> out = new LinkedHashMap<>();
        if (actualSize <= 0) {
            return out;
        }

        int nAbundant = 0;
        int nDeficient = 0;
        int nPrime = 0;
        int nHappy = 0;
        long sumOmega = 0;
        long sumBigOmega = 0;
        long sumDivisorCount = 0;
        int maxDivisorCount = 0;
        int mertensDelta = 0;

        for (int n = actualStart; n < end; n++) {
            long s = sigma(n);
            if (s > 2L * n) nAbundant++;
            else if (s < 2L * n) nDeficient++;
            // perfect (s == 2n) excluded by design

            if (isPrime(n)) nPrime++;
            if (isHappy(n)) nHappy++;

            sumOmega += omega(n);
            sumBigOmega += bigOmega(n);
            int d = divisorCount(n);
            sumDivisorCount += d;
            if (d > maxDivisorCount) maxDivisorCount = d;
            mertensDelta += mobius(n);
        }

        double inv = 1.0 / actualSize;
        out.put("abundant_density",   nAbundant * inv);
        out.put("deficient_density",  nDeficient * inv);
        out.put("prime_density",      nPrime * inv);
        out.put("happy_density",      nHappy * inv);
        out.put("mean_omega",         sumOmega * inv);
        out.put("mean_Omega",         sumBigOmega * inv);
        out.put("mean_divisor_count", sumDivisorCount * inv);
        out.put("max_divisor_count",  (double) maxDivisorCount);
        out.put("mertens_delta",      (double) mertensDelta);
        return out;
    }
}
