package com.emergentterrain.research.discovery;

import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 30 frontier-escalation — the trusted {@link ScaleValueSource} for the
 * Dirichlet divisor-sum frontier (the divisor-bound analog of {@link
 * SidecarMertensValueSource}): computes {@code D(x) = Σ_{n=1}^{x} d(n)} for
 * {@code x ≤ NT_MAX_N} from the sieve via the existing number-theory window.
 *
 * <p>The sidecar window aggregator sums over {@code [max(2,start), start+size)}
 * and returns {@code mean_divisor_count = sum_divisor_count / actual_size}. For
 * {@code evaluateWindow(1, x)} that is {@code actual_start=2}, {@code
 * actual_size=x-1}, so {@code Σ_{n=2}^{x} d(n) = round(mean · (x-1))} and {@code
 * D(x) = that + d(1) = that + 1} — exact (the round-trip recovers the integer
 * sum; {@code d(1)=1} is added back the way {@link SidecarMertensValueSource}
 * adds {@code μ(1)}). {@code D(1)=1} is special-cased because the {@code [1,2)}
 * window is empty sidecar-side. Returns null for out-of-range x or any sidecar
 * error (→ the loop treats it as can't-compute).
 */
@Component
public class SidecarDivisorSumValueSource implements ScaleValueSource {

    private final MathConjectureSubstrateDelegate substrate;

    public SidecarDivisorSumValueSource(MathConjectureSubstrateDelegate substrate) {
        this.substrate = substrate;
    }

    @Override
    public Long valueAt(long x) {
        if (x < 1 || x > ConjectureVerifier.MAX_N) {
            return null;
        }
        if (x == 1) {
            return 1L;   // D(1) = d(1) = 1; the [1,2) window has actual_size 0 (empty sidecar-side)
        }
        try {
            Map<String, Double> window = substrate.evaluateWindow(1, (int) x);   // sums d(n), n=2..x
            Double mean = window.get("mean_divisor_count");
            if (mean == null) {
                return null;
            }
            long actualSize = x - 1;   // end_=1+x, actual_start=max(2,1)=2 → actual_size = x-1
            return Math.round(mean * actualSize) + 1L;   // + d(1)
        } catch (RuntimeException e) {
            return null;
        }
    }
}
