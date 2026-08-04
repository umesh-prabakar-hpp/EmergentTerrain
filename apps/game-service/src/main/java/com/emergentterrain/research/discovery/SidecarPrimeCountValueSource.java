package com.emergentterrain.research.discovery;

import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 30 frontier-escalation (Path A) — the trusted {@link ScaleValueSource} for the
 * prime-counting frontier (the π(x) analog of {@link SidecarDivisorSumValueSource} and
 * {@link SidecarMertensValueSource}): computes {@code π(x) = #{p ≤ x : p prime}} for
 * {@code x ≤ NT_MAX_N} from the sieve via the existing number-theory window.
 *
 * <p>The sidecar window aggregator reports {@code prime_density = n_prime / actual_size}
 * over {@code [max(2,start), start+size)}. For {@code evaluateWindow(1, x)} that is
 * {@code actual_start=2}, {@code actual_size=x-1}, and {@code n_prime} = the number of
 * primes in {@code [2, x]} = {@code π(x)} (1 is not prime). So
 * {@code π(x) = round(prime_density · (x-1))} — exact (the round-trip recovers the integer
 * count). {@code π(1)=0} is special-cased because the {@code [1,2)} window is empty
 * sidecar-side. Returns null for out-of-range x or any sidecar error (→ the loop treats it
 * as can't-compute), exactly like the divisor source.
 */
@Component
public class SidecarPrimeCountValueSource implements ScaleValueSource {

    private final MathConjectureSubstrateDelegate substrate;

    public SidecarPrimeCountValueSource(MathConjectureSubstrateDelegate substrate) {
        this.substrate = substrate;
    }

    @Override
    public Long valueAt(long x) {
        if (x < 1 || x > ConjectureVerifier.MAX_N) {
            return null;
        }
        if (x == 1) {
            return 0L;   // π(1) = 0; the [1,2) window has actual_size 0 (empty sidecar-side)
        }
        try {
            Map<String, Double> window = substrate.evaluateWindow(1, (int) x);   // primes in [2, x]
            Double density = window.get("prime_density");
            if (density == null) {
                return null;
            }
            long actualSize = x - 1;   // end_=1+x, actual_start=max(2,1)=2 → actual_size = x-1
            return Math.round(density * actualSize);   // = π(x); no +1 (1 is not prime)
        } catch (RuntimeException e) {
            return null;
        }
    }
}
