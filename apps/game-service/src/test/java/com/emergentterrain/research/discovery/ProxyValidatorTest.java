package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b — the proxy moat: judge candidate values against the oracle on
 * the overlap.
 */
class ProxyValidatorTest {

    private static ProxyOracle oracle(Map<Long, Long> truth) {
        return new ProxyOracle() {
            public String name() { return "t"; }
            public Long valueAt(long x) { return truth.get(x); }
        };
    }

    @Test
    void acceptsWhenAllOverlapValuesMatch() {
        ProxyOracle o = oracle(Map.of(10L, 5L, 100L, 50L));
        assertThat(ProxyValidator.judge(Map.of(10L, 5L, 100L, 50L), o, List.of(10L, 100L)).accepted()).isTrue();
    }

    @Test
    void rejectsWithFirstFail() {
        ProxyOracle o = oracle(Map.of(10L, 5L, 100L, 50L));
        ProxyValidator.Verdict v = ProxyValidator.judge(Map.of(10L, 5L, 100L, 99L), o, List.of(10L, 100L));
        assertThat(v.accepted()).isFalse();
        assertThat(v.firstFailK()).isEqualTo(100L);
        assertThat(v.got()).isEqualTo(99L);
        assertThat(v.want()).isEqualTo(50L);
    }

    @Test
    void skipsCheckpointsBeyondOracleRange() {
        ProxyOracle o = oracle(Map.of(10L, 5L));   // valueAt(100) -> null (out of range)
        assertThat(ProxyValidator.judge(Map.of(10L, 5L), o, List.of(10L, 100L)).accepted()).isTrue();
    }

    @Test
    void rejectsWhenCandidateMissingAValue() {
        ProxyOracle o = oracle(Map.of(10L, 5L));
        assertThat(ProxyValidator.judge(Map.of(), o, List.of(10L)).accepted()).isFalse();
    }
}
