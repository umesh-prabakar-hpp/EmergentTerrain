package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MathToolSuiteVerifierMetricsTest {

    @Test
    void completedVerifierLaddersAreRecognizedAsComputationalSuccesses() {
        assertThat(MathToolSuite.isSuccessResult(
                "verify_constant scale-ladder: cramer_gap_ratio measured across 5 scales.")).isTrue();
        assertThat(MathToolSuite.isSuccessResult(
                "verify_constant scale-ladder unavailable: the Julia conjecture sidecar is not ready.")).isFalse();
        assertThat(MathToolSuite.isSuccessResult(
                "verify_oscillation scale-ladder: dirichlet_error measured across 5 scales.")).isTrue();
        assertThat(MathToolSuite.isSuccessResult(
                "verify_growth scale-ladder: max_divisor_count measured across 5 scales.")).isTrue();
    }

    @Test
    void everySupportedVerifierCounterFeedsTheD3SuccessTotal() {
        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();

        metrics.incrementVerifyConjectureSuccesses();
        metrics.incrementVerifyConstantSuccesses();
        metrics.incrementVerifyOscillationSuccesses();
        metrics.incrementVerifyGrowthSuccesses();
        metrics.incrementScalarWindowStatsSuccesses();

        assertThat(metrics.totalSuccesses()).isEqualTo(5);
        metrics.reset();
        assertThat(metrics.totalSuccesses()).isZero();
    }
}
