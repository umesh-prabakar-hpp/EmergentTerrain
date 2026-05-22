package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-22 — unit tests for {@link MathToolSuite.ToolMetrics#reset()}.
 * The reset path is exercised by
 * {@link LlmRouter#callPrimaryWithTools(String, String, String,
 * java.util.function.Function, Runnable)} when a handle attempt throws
 * and the router moves to the next handle. Without reset, partial
 * success counts from the discarded attempt would leak into the
 * successful response's metrics — the root cause of the P-a328-865 /
 * 880 / 890 prof:ok-but-empty-trace divergence.
 */
class ToolMetricsResetTest {

    @Test
    void allCountersClearedAfterReset() {
        MathToolSuite.ToolMetrics m = new MathToolSuite.ToolMetrics();
        m.incrementSandboxSuccesses();
        m.incrementSymbolicSuccesses();
        m.incrementGpuEvalSuccesses();
        m.incrementPredicateScanSuccesses();
        m.incrementSandboxRejects();
        m.incrementSymbolicRejects();
        m.incrementGpuEvalRejects();
        // Sanity: all incremented before reset.
        assertThat(m.totalSuccesses()).isEqualTo(4);
        assertThat(m.totalRejects()).isEqualTo(3);

        m.reset();

        assertThat(m.sandboxSuccessCount()).isZero();
        assertThat(m.symbolicSuccessCount()).isZero();
        assertThat(m.gpuEvalSuccessCount()).isZero();
        assertThat(m.predicateScanSuccessCount()).isZero();
        assertThat(m.sandboxRejectCount()).isZero();
        assertThat(m.symbolicRejectCount()).isZero();
        assertThat(m.gpuEvalRejectCount()).isZero();
        assertThat(m.totalSuccesses()).isZero();
        assertThat(m.totalRejects()).isZero();
    }

    @Test
    void resetIsIdempotent() {
        MathToolSuite.ToolMetrics m = new MathToolSuite.ToolMetrics();
        m.reset();
        m.reset();
        assertThat(m.totalSuccesses()).isZero();
        assertThat(m.totalRejects()).isZero();
    }

    @Test
    void countersIncrementAfterReset() {
        // After a reset, the object is reusable — counters keep counting.
        MathToolSuite.ToolMetrics m = new MathToolSuite.ToolMetrics();
        m.incrementSandboxSuccesses();
        m.reset();
        m.incrementSandboxSuccesses();
        m.incrementSandboxSuccesses();
        assertThat(m.sandboxSuccessCount()).isEqualTo(2);
        assertThat(m.totalSuccesses()).isEqualTo(2);
    }
}
