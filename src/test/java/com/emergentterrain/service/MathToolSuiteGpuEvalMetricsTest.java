package com.emergentterrain.service;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.v1.EvalExprRequest;
import com.emergentterrain.gpu.v1.EvalExprResponse;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8 Tier 4 — pins the dispatch + telemetry flow for the run_gpu_eval
 * tool. Mocks the GpuEngineAdapter so the test never starts a real Rust
 * engine; what matters here is:
 *
 * <ul>
 *   <li>A formula that passes the static check reaches the adapter, and the
 *       adapter's response is surfaced verbatim to the LLM.</li>
 *   <li>A formula that fails the static check is blocked before the adapter
 *       is called, and {@code gpuEvalRejectCount} increments.</li>
 *   <li>When MathToolSuite is constructed without an adapter (the legacy
 *       2-arg constructor used in tests + dev configs without the Rust
 *       engine), {@code run_gpu_eval} surfaces a "not configured" message
 *       rather than NPE-ing.</li>
 * </ul>
 */
class MathToolSuiteGpuEvalMetricsTest {

    @Test
    void approvedFormulaDispatchesToAdapter() throws Exception {
        GpuEngineAdapter adapter = mock(GpuEngineAdapter.class);
        when(adapter.evalExpr(any(EvalExprRequest.class))).thenReturn(
                EvalExprResponse.newBuilder()
                        .setMean(2.5).setStdDev(1.118).setMin(1.0).setMax(4.0)
                        .setSum(10.0).setN(4).setFiniteCount(4)
                        .setDebugJson("{}")
                        .build());

        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), mapper, adapter);
        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_gpu_eval",
                "arguments", java.util.Map.of(
                        "formula",   "sqrt(n) + sin(n)",
                        "start",     1,
                        "end",       1000,
                        "rationale", "verify mean of sqrt+sin claim"
                )));

        String result = executor.apply(toolCall);

        assertThat(result).contains("GPU eval").contains("mean=2.500").contains("n=4");
        assertThat(metrics.gpuEvalRejectCount()).isZero();
        verify(adapter).evalExpr(any(EvalExprRequest.class));
    }

    @Test
    void rejectedFormulaSkipsAdapterAndIncrementsCounter() throws Exception {
        GpuEngineAdapter adapter = mock(GpuEngineAdapter.class);
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), mapper, adapter);
        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        // isPrime is a sandbox builtin but NOT in the GPU allow list — must be
        // rejected by the Java-side static check before reaching the adapter.
        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_gpu_eval",
                "arguments", java.util.Map.of(
                        "formula",   "isPrime(n) * n",
                        "start",     1,
                        "end",       1000,
                        "rationale", "(test)"
                )));

        String result = executor.apply(toolCall);

        assertThat(result).startsWith("REJECTED by safety review");
        assertThat(result).contains("isPrime");
        assertThat(metrics.gpuEvalRejectCount()).isEqualTo(1);
        verify(adapter, never()).evalExpr(any(EvalExprRequest.class));
    }

    @Test
    void missingAdapterSurfacesNotConfigured() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Legacy 2-arg constructor — no GpuEngineAdapter.
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), mapper);
        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_gpu_eval",
                "arguments", java.util.Map.of(
                        "formula",   "sqrt(n)",
                        "start",     1,
                        "end",       1000,
                        "rationale", "(test)"
                )));

        String result = executor.apply(toolCall);

        assertThat(result).contains("not configured");
        // "not configured" is operational, not a safety rejection — counter stays 0.
        assertThat(metrics.gpuEvalRejectCount()).isZero();
    }

    @Test
    void rangeTooLargeIsRejectedBeforeAdapter() throws Exception {
        GpuEngineAdapter adapter = mock(GpuEngineAdapter.class);
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), mapper, adapter);
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_gpu_eval",
                "arguments", java.util.Map.of(
                        "formula",   "sqrt(n)",
                        "start",     0,
                        "end",       50_000_000,            // > MAX_RANGE
                        "rationale", "(test)"
                )));

        String result = executor.apply(toolCall);

        assertThat(result).contains("too large");
        verify(adapter, never()).evalExpr(any(EvalExprRequest.class));
    }

    @Test
    void invertedRangeIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(mock(LlmRouter.class), mapper, mock(GpuEngineAdapter.class));
        var executor = suite.buildExecutor(stubDraft(), new MathToolSuite.ToolMetrics());

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_gpu_eval",
                "arguments", java.util.Map.of(
                        "formula",   "sqrt(n)",
                        "start",     100,
                        "end",       50,
                        "rationale", "(test)"
                )));

        assertThat(executor.apply(toolCall)).contains("end must be > start");
    }

    /** Minimum-viable PaperDraft for the executor's domain/topic lookup.
     *  Kept in sync with {@link MathToolSuiteSandboxMetricsTest#stubDraft()}. */
    private static PaperDraft stubDraft() {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Collatz Stopping Time",
                "Test", "raw", "h", "c", "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
