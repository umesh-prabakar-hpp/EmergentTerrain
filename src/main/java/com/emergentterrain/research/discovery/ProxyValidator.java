package com.emergentterrain.research.discovery;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 fork 2b — the proxy moat: judge an LLM proxy method's emitted values
 * against a {@link ProxyOracle} on the overlap checkpoints (exact integer
 * equality). The generalized analog of the M(x) moat — same contract, any proxy.
 * Pure.
 */
public final class ProxyValidator {

    private ProxyValidator() {}

    public record Verdict(boolean accepted, Long firstFailK, Long got, Long want) {
        public static Verdict accept() {
            return new Verdict(true, null, null, null);
        }
    }

    public static Verdict judge(Map<Long, Long> candidateValues, ProxyOracle oracle, List<Long> checkpoints) {
        for (long k : checkpoints) {
            Long want = oracle.valueAt(k);
            if (want == null) {
                continue;   // beyond the oracle's range — not part of the overlap check
            }
            Long got = candidateValues == null ? null : candidateValues.get(k);
            if (got == null || !got.equals(want)) {
                return new Verdict(false, k, got, want);
            }
        }
        return Verdict.accept();
    }
}
