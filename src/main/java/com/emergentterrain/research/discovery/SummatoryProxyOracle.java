package com.emergentterrain.research.discovery;

import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 30 fork 2b — builds a {@link ProxyOracle} for a {@link ProxySpec} from the
 * trusted sieve. Reuses the window aggregator, which already sums μ, ω, Ω, τ over
 * a block: {@code window(1, x)} covers {@code [2, x]} (it skips n=1), so
 * {@code Σ_{2..x} μ = mertens_delta}, {@code Σ_{2..x} ω = mean_omega·(x−1)}, etc.;
 * the n=1 terms (μ(1)=1, ω(1)=0, Ω(1)=0, τ(1)=1) are added back. Exact on the
 * overlap (the mean×size round-trip is well within 0.5 for x ≤ NT_MAX_N). No new
 * sidecar code — so no recompile.
 */
@Component
public class SummatoryProxyOracle {

    private final MathConjectureSubstrateDelegate substrate;

    public SummatoryProxyOracle(MathConjectureSubstrateDelegate substrate) {
        this.substrate = substrate;
    }

    public ProxyOracle forSpec(ProxySpec spec) {
        return new ProxyOracle() {
            @Override
            public String name() {
                return spec.name();
            }

            @Override
            public Long valueAt(long x) {
                if (x < 1 || x > ConjectureVerifier.MAX_N) {
                    return null;
                }
                if (x == 1) {
                    return summatoryFromSums(spec, 1, 0, 0, 1);   // n=1 only: μ(1)=1, τ(1)=1
                }
                try {
                    return summatory(spec, substrate.evaluateWindow(1, (int) x), x - 1);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        };
    }

    /**
     * Pure: combine the {@code window(1, x)} aggregates (sums over {@code [2, x]},
     * {@code size = x − 1}) plus the n=1 terms into the proxy value. Static for
     * unit testing without the sidecar.
     */
    static long summatory(ProxySpec spec, Map<String, Double> window, long size) {
        long sumMu = Math.round(window.get("mertens_delta")) + 1;                // + μ(1)=1
        long sumOmega = Math.round(window.get("mean_omega") * size);             // ω(1)=0
        long sumBigOmega = Math.round(window.get("mean_Omega") * size);          // Ω(1)=0
        long sumTau = Math.round(window.get("mean_divisor_count") * size) + 1;   // + τ(1)=1
        return summatoryFromSums(spec, sumMu, sumOmega, sumBigOmega, sumTau);
    }

    static long summatoryFromSums(ProxySpec spec, long sumMu, long sumOmega, long sumBigOmega, long sumTau) {
        double v = spec.cMu() * sumMu + spec.cOmega() * sumOmega
                + spec.cBigOmega() * sumBigOmega + spec.cTau() * sumTau;
        return Math.round(v);
    }
}
