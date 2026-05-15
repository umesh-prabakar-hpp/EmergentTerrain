package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 C3 — pins {@link MathToolSuite.ToolMetrics#sandboxRejectCount} so the
 * persisted LLM Call Path can carry the count, letting reviewers distinguish
 * "result was computed" from "result was hand-waved after sandbox refused".
 */
class MathToolSuiteSandboxMetricsTest {

    @Test
    void sandboxRejectIncrementsToolMetricsCounter() throws Exception {
        // 4b reviewer rejects everything → every run_sandboxed_code call comes back
        // with the "REJECTED by safety review" prefix.
        LlmRouter router = mock(LlmRouter.class);
        when(router.callFast(anyString())).thenReturn("REJECTED: synthetic test refusal");
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(router, mapper);

        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        // Build a minimal sandbox tool-call JSON.
        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_sandboxed_code",
                "arguments", java.util.Map.of(
                        "code",        "var x = 1; return x;",
                        "description", "trivial code",
                        "range_start", 1,
                        "range_end",   10
                )));

        String result1 = executor.apply(toolCall);
        String result2 = executor.apply(toolCall);

        assertThat(result1).startsWith("REJECTED by safety review");
        assertThat(result2).startsWith("REJECTED by safety review");
        assertThat(metrics.sandboxRejectCount()).isEqualTo(2);
    }

    @Test
    void successfulSandboxRunDoesNotIncrementCounter() throws Exception {
        // 4b reviewer approves; the sandbox runs the code successfully.
        LlmRouter router = mock(LlmRouter.class);
        when(router.callFast(anyString())).thenReturn("APPROVED");
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(router, mapper);

        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_sandboxed_code",
                "arguments", java.util.Map.of(
                        "code",        "var s = 0; for (var n = start; n < end; n++) { s += 1; } return s;",
                        "description", "count integers in range",
                        "range_start", 1,
                        "range_end",   100
                )));

        String result = executor.apply(toolCall);

        assertThat(result).startsWith("Sandboxed computation");
        assertThat(metrics.sandboxRejectCount()).isZero();
    }

    @Test
    void otherToolFailuresDoNotIncrementSandboxCounter() throws Exception {
        // A REJECTED string from run_param_sweep (e.g. invalid params) must not
        // count toward sandbox-reject — the counter is scoped to run_sandboxed_code.
        LlmRouter router = mock(LlmRouter.class);
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(router, mapper);

        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_param_sweep",
                "arguments", java.util.Map.of(
                        "param1",    -1,    // invalid: must be 10..99
                        "param2",     2.0,
                        "rationale", "test"
                )));

        executor.apply(toolCall);
        assertThat(metrics.sandboxRejectCount()).isZero();
    }

    // ── Phase 10 — success counters + normalizeEqualHead ────────────────────

    @Test
    void successfulSandboxIncrementsSuccessCounter() throws Exception {
        // Successful sandbox calls must now increment sandboxSuccessCount so
        // the professor stage can tell "all tools failed" apart from "some
        // tools succeeded but the synthesis was light on detail".
        LlmRouter router = mock(LlmRouter.class);
        when(router.callFast(anyString())).thenReturn("APPROVED");
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(router, mapper);

        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String toolCall = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_sandboxed_code",
                "arguments", java.util.Map.of(
                        "code",        "var s = 0; for (var n = start; n < end; n++) { s += 1; } return s;",
                        "description", "count integers in range",
                        "range_start", 1,
                        "range_end",   100
                )));

        executor.apply(toolCall);
        executor.apply(toolCall);

        assertThat(metrics.sandboxSuccessCount()).isEqualTo(2);
        assertThat(metrics.sandboxRejectCount()).isZero();
        assertThat(metrics.totalSuccesses()).isEqualTo(2);
        assertThat(metrics.totalRejects()).isZero();
    }

    @Test
    void rejectAndSuccessTalliesAreIndependent() throws Exception {
        // One rejected call + one successful call → 1 reject and 1 success
        // recorded separately. Confirms the Phase 12 D3 zero-successes gate
        // (totalSuccesses == 0) wouldn't fire here — a single successful tool
        // call is sufficient to land the paper, regardless of how many rejects
        // accumulate alongside it.
        LlmRouter router = mock(LlmRouter.class);
        ObjectMapper mapper = new ObjectMapper();
        // First call: 4b says REJECTED. Second call: APPROVED.
        when(router.callFast(anyString()))
                .thenReturn("REJECTED: first call refused")
                .thenReturn("APPROVED");
        MathToolSuite suite = new MathToolSuite(router, mapper);

        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String tool = mapper.writeValueAsString(java.util.Map.of(
                "name", "run_sandboxed_code",
                "arguments", java.util.Map.of(
                        "code", "var s = 0; for (var n = start; n < end; n++) { s += 1; } return s;",
                        "description", "ok",
                        "range_start", 1,
                        "range_end",   10)));

        executor.apply(tool);
        executor.apply(tool);

        assertThat(metrics.sandboxRejectCount()).isEqualTo(1);
        assertThat(metrics.sandboxSuccessCount()).isEqualTo(1);
    }

    @Test
    void normalizeEqualHead_rewritesSingleEquals() {
        // P-a328-364 verbatim: `Solve[Q = 1/2 / Sqrt[L/C], R]` was blocked by
        // the Symja whitelist because `=` is the Set head. The LLM intended
        // Equal (`==`); normalizeEqualHead fixes that without touching the
        // semantically distinct operators ==, <=, >=, !=, :=.
        assertThat(MathToolSuite.normalizeEqualHead(
                "Solve[Q = 1/2 / Sqrt[L/C], R]"))
                .isEqualTo("Solve[Q == 1/2 / Sqrt[L/C], R]");
    }

    @Test
    void normalizeEqualHead_preservesComparisonOperators() {
        assertThat(MathToolSuite.normalizeEqualHead("a == b")).isEqualTo("a == b");
        assertThat(MathToolSuite.normalizeEqualHead("a <= b")).isEqualTo("a <= b");
        assertThat(MathToolSuite.normalizeEqualHead("a >= b")).isEqualTo("a >= b");
        assertThat(MathToolSuite.normalizeEqualHead("a != b")).isEqualTo("a != b");
        assertThat(MathToolSuite.normalizeEqualHead("f := x^2")).isEqualTo("f := x^2");
    }

    @Test
    void normalizeEqualHead_rewritesMultipleSetHeadsInOneExpression() {
        // System of equations: Solve[{x = y + 1, y = 2*x}, {x, y}]
        assertThat(MathToolSuite.normalizeEqualHead(
                "Solve[{x = y + 1, y = 2*x}, {x, y}]"))
                .isEqualTo("Solve[{x == y + 1, y == 2*x}, {x, y}]");
    }

    @Test
    void normalizeEqualHead_handlesNullSafely() {
        assertThat(MathToolSuite.normalizeEqualHead(null)).isNull();
        assertThat(MathToolSuite.normalizeEqualHead("")).isEmpty();
    }

    /** Minimum-viable PaperDraft for the executor's domain/topic lookup. */
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
