package com.emergentterrain.research.discovery;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b — the generalized B3 oracle's pure math (combining the window
 * aggregates into a proxy summatory) + its range guards. No mocking (JDK 25 +
 * Mockito can't instrument the concrete delegate).
 */
class SummatoryProxyOracleTest {

    private static final Map<String, Double> WINDOW_100 = Map.of(
            "mertens_delta", 1.0, "mean_omega", 2.0, "mean_Omega", 2.5, "mean_divisor_count", 4.0);

    @Test
    void combinesAggregatesAndN1Terms() {
        long size = 99;   // window(1,100) covers [2,100]
        // Σμ[1..100] = mertens_delta(1) + μ(1)(1) = 2  (agrees with the M(x) oracle)
        assertThat(SummatoryProxyOracle.summatory(ProxyCatalog.MERTENS_SUM, WINDOW_100, size)).isEqualTo(2L);
        // Σω[1..100] = round(2.0×99) + ω(1)(0) = 198
        assertThat(SummatoryProxyOracle.summatory(ProxyCatalog.OMEGA_SUM, WINDOW_100, size)).isEqualTo(198L);
        // Σ(Ω−ω) = round(2.5×99) − round(2.0×99) = 248 − 198 = 50
        assertThat(SummatoryProxyOracle.summatory(ProxyCatalog.OMEGA_EXCESS, WINDOW_100, size)).isEqualTo(50L);
    }

    @Test
    void n1OnlyTerms() {
        assertThat(SummatoryProxyOracle.summatoryFromSums(ProxyCatalog.MERTENS_SUM, 1, 0, 0, 1)).isEqualTo(1L);
        assertThat(SummatoryProxyOracle.summatoryFromSums(new ProxySpec("tau", 0, 0, 0, 1), 1, 0, 0, 1)).isEqualTo(1L);
    }

    @Test
    void rangeGuardsDoNotTouchTheSidecar() {
        // a null-client delegate would fail if called; the guards (x<1, x>NT_MAX_N, x==1) return first.
        SummatoryProxyOracle f = new SummatoryProxyOracle(
                new MathConjectureSubstrateDelegate((JuliaMathClient) null));
        ProxyOracle o = f.forSpec(ProxyCatalog.MERTENS_SUM);
        assertThat(o.valueAt(0)).isNull();
        assertThat(o.valueAt(2_000_001L)).isNull();
        assertThat(o.valueAt(1)).isEqualTo(1L);          // n=1 path — no delegate call
    }
}
