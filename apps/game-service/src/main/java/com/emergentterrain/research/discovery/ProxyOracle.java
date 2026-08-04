package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — the generalized B3 oracle: trusted ground truth for a proxy
 * quantity on the overlap range. {@link #valueAt} returns the exact value for
 * {@code 1 ≤ x ≤ NT_MAX_N} (computed by trusted primitives, independent of any
 * LLM method) or {@code null} beyond it. A proxy method proposed by B3 is
 * moat-validated against this on the overlap, then trusted out of range — the
 * same candidate-emits / trusted-judges contract as M(x), now for any proxy.
 */
public interface ProxyOracle {

    String name();

    Long valueAt(long x);
}
