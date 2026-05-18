package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 P8b — pins the {@link MathToolSuite#isSuccessResult(String)}
 * default-deny classifier so the D3
 * ({@code zero_successes→suppressed}) gate sees a real 0 when no
 * computational result was actually produced.
 *
 * <p>The bug this test class prevents: prior to Phase 17 P8b, the
 * classifier was "rejected OR (NOT in failed-prefix list) → success".
 * Invalid-arg returns ("Missing expression.", "Invalid range:", etc.)
 * and unknown error formats slipped into the success bucket, letting
 * papers like P-a328-613 and P-a328-623 (5/5 {@code run_symbolic}
 * rejections / timeouts) reach {@code prof:ok} with the D3 gate
 * believing a real computation happened.
 *
 * <p>The fix is default-deny: count as success ONLY when the result
 * starts with a known success-marker prefix. All these tests pin both
 * directions — known successes increment, all the leak shapes don't.
 */
class MathToolSuiteCounterSymmetryTest {

    // ── Known success prefixes — must count ──────────────────────────────────

    @Test
    void paramSweepSuccessRecognized() {
        assertThat(MathToolSuite.isSuccessResult(
                "Param sweep (param1=40, param2=6.0): VdW result: η=0.788 ...")).isTrue();
    }

    @Test
    void predicateScanSuccessRecognized() {
        assertThat(MathToolSuite.isSuccessResult(
                "Predicate scan: Dynamic [50,100) \"professor predicate scan\": Mean of collatzSteps over 50 = 42")).isTrue();
    }

    @Test
    void symbolicResultRecognized() {
        // Real example from P-557 call 3: numerical Solve result.
        assertThat(MathToolSuite.isSuccessResult(
                "Symbolic result for N[h/(2*Sqrt[2]*Sqrt[k*m*T])]: output=3.814e-9")).isTrue();
    }

    @Test
    void symbolicEvaluationSuccessRecognized() {
        // Real example: f(n)=5*101325/1e6 evaluation
        assertThat(MathToolSuite.isSuccessResult(
                "Symbolic evaluation of f(n)=5 * 101325 / 1e6 over [1,2): mean=0.5066")).isTrue();
    }

    @Test
    void gpuEvalSuccessRecognized() {
        assertThat(MathToolSuite.isSuccessResult(
                "GPU eval of f(n)=2*pi*sqrt(L/g) over [1,100): mean=2.0 std=0.5 ...")).isTrue();
    }

    @Test
    void sandboxedComputationSuccessRecognized() {
        // Real example from P-557 call 5: critical well width.
        assertThat(MathToolSuite.isSuccessResult(
                "Sandboxed computation — \"Compute the critical well width\" over [1,2): result=3.81386e-09")).isTrue();
    }

    // ── Safety rejections — must NOT count as success ────────────────────────

    @Test
    void safetyRejectionNotSuccess() {
        // Real example from P-557 call 2: parse failure on '^' char.
        assertThat(MathToolSuite.isSuccessResult(
                "REJECTED by safety review: parse failed: Unexpected char '^' line 1 ...")).isFalse();
    }

    @Test
    void staticRejectionNotSuccess() {
        // Real example: member-access pre-scan rejection from refresh #3.
        assertThat(MathToolSuite.isSuccessResult(
                "REJECTED by safety review: REJECTED (static): member access not supported: '.asin' ...")).isFalse();
    }

    @Test
    void semanticRejectionNotSuccess() {
        // Real example: 4b semantic reject from P-576.
        assertThat(MathToolSuite.isSuccessResult(
                "REJECTED by safety review: REJECTED: The code attempts to compute prime gaps but uses an arbitrary range ...")).isFalse();
    }

    // ── Runtime failures — must NOT count as success ─────────────────────────

    @Test
    void symbolicEvaluationFailedNotSuccess() {
        // Real example from P-613 call 5: timeout.
        assertThat(MathToolSuite.isSuccessResult(
                "Symbolic evaluation failed: Symbolic evaluation timed out after 3000 ms")).isFalse();
    }

    @Test
    void sandboxExecutionFailedNotSuccess() {
        // Real example from P-617 call 3: loop limit.
        assertThat(MathToolSuite.isSuccessResult(
                "Sandbox execution failed: Loop limit exceeded")).isFalse();
    }

    @Test
    void gpuEvalFailedNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "GPU eval failed: device timed out")).isFalse();
    }

    @Test
    void predicateScanFailedNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Predicate scan failed: internal error")).isFalse();
    }

    @Test
    void toolExecutionErrorNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Tool execution error: NullPointerException")).isFalse();
    }

    // ── Invalid-arg / refusal returns — THE PRE-PHASE-17 LEAKS ───────────────

    @Test
    void missingExpressionNotSuccess() {
        // Pre-Phase-17 leak #1: "Missing expression." matched neither REJECTED
        // nor the failed-prefix list → counted as success. Must be false now.
        assertThat(MathToolSuite.isSuccessResult("Missing expression.")).isFalse();
    }

    @Test
    void missingCodeNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult("Missing code.")).isFalse();
    }

    @Test
    void missingFormulaNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult("Missing formula.")).isFalse();
    }

    @Test
    void missingStartEndNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Missing 'start' or 'end' integer.")).isFalse();
    }

    @Test
    void invalidRangeNotSuccess() {
        // Pre-Phase-17 leak #2: "Invalid range:" — also matched neither
        // bucket. Real example: P-603 call 1 ("Invalid range: must be ≥1").
        assertThat(MathToolSuite.isSuccessResult(
                "Invalid range: must be ≥1 and span ≤ 5000")).isFalse();
    }

    @Test
    void invalidParamsNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Invalid params: param1 must be 10–99, param2 must be 1.0–9.0.")).isFalse();
    }

    @Test
    void invalidMetricNotSuccess() {
        // Real example from refresh #2's P-577 call 4.
        assertThat(MathToolSuite.isSuccessResult(
                "Invalid metric 'min_of:collatzSteps'. Use: [mean_of:collatzSteps, ...]")).isFalse();
    }

    @Test
    void invalidPredicateScanRangeNotSuccess() {
        // Real example from prior corpus's divisor-sum-average runs.
        assertThat(MathToolSuite.isSuccessResult(
                "Invalid predicate scan: range must be ≥1 and span ≤ 500")).isFalse();
    }

    @Test
    void invalidPredicateTokenNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Invalid predicate token: 'isSquarefree'. Use: [isPrime, ...]")).isFalse();
    }

    @Test
    void topicNotComputableNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Topic not computable via MathPhysicsEngine.")).isFalse();
    }

    @Test
    void paramSweepFailedNotSuccess() {
        // Pre-Phase-17 leak: this prefix was not in the "failed" list.
        assertThat(MathToolSuite.isSuccessResult(
                "Param sweep failed: NPE in compute()")).isFalse();
    }

    @Test
    void rangeTooLargeNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "Range too large: 6000 samples (max 5000)")).isFalse();
    }

    @Test
    void gpuEvalNotConfiguredNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(
                "run_gpu_eval not configured: no GpuEngineAdapter wired into this ...")).isFalse();
    }

    @Test
    void unknownToolNotSuccess() {
        // The default branch of the switch: "Unknown tool: <name>".
        assertThat(MathToolSuite.isSuccessResult(
                "Unknown tool: run_xyz")).isFalse();
    }

    // ── Defensive edges ──────────────────────────────────────────────────────

    @Test
    void nullResultNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult(null)).isFalse();
    }

    @Test
    void emptyResultNotSuccess() {
        assertThat(MathToolSuite.isSuccessResult("")).isFalse();
    }

    @Test
    void whitespaceOnlyNotSuccess() {
        // No success prefix matches, so it doesn't count. The strict
        // startsWith check makes leading-whitespace irrelevant — we
        // would NOT match "  Symbolic result for ..." because the prefix
        // doesn't start with whitespace.
        assertThat(MathToolSuite.isSuccessResult("   ")).isFalse();
    }
}
