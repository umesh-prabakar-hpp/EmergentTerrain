package com.emergentterrain.research.discovery;

import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 30 frontier-escalation Phase 4 — the production {@link ScaleValueSource}
 * for the Mertens escalation: computes {@code M(x)} for {@code x ≤ NT_MAX_N} from
 * the sieve via the existing window delegate. The window aggregator sums μ over
 * {@code [max(2,start), start+size)}, so {@code window(1, x).mertens_delta} is
 * {@code Σ_{n=2}^{x} μ(n) = M(x) − μ(1)}, and {@code M(x) = mertens_delta + 1}.
 * Returns null for out-of-range x or any sidecar error (→ the loop treats it as
 * can't-compute, but in practice the loop only calls this for x ≤ NT_MAX_N).
 */
@Component
public class SidecarMertensValueSource implements ScaleValueSource {

    private final MathConjectureSubstrateDelegate substrate;

    public SidecarMertensValueSource(MathConjectureSubstrateDelegate substrate) {
        this.substrate = substrate;
    }

    @Override
    public Long valueAt(long x) {
        if (x < 1 || x > ConjectureVerifier.MAX_N) {
            return null;
        }
        try {
            Map<String, Double> window = substrate.evaluateWindow(1, (int) x);
            Double mertensDelta = window.get("mertens_delta");
            return mertensDelta == null ? null : Math.round(mertensDelta) + 1L;   // + μ(1)
        } catch (RuntimeException e) {
            return null;
        }
    }
}
