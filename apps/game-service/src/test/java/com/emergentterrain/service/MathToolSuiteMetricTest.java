package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-08 review rec 3 — {@code run_predicate_scan} metric aliasing.
 * The professor stage kept emitting free-form metric strings that the
 * exact-token valid-set rejected, burning 1–3 tool rounds per density
 * paper (P-b72c-308/-309/-313). {@link MathToolSuite#normalizeMetric}
 * collapses the common "fraction …" phrasings to the sole density metric;
 * genuinely-unsupported requests (divisor count, omega) still fall through
 * to the valid-set rejection by design.
 */
class MathToolSuiteMetricTest {

    // ── The live misuse strings from the 2026-06-08 batch now alias ──────────

    @Test
    void fractionSatisfyingPredicate_aliasesToFractionSatisfying() {
        // P-b72c-308: "fraction satisfying predicate" (trailing word) missed.
        assertThat(MathToolSuite.normalizeMetric("fraction satisfying predicate"))
                .isEqualTo("fraction_satisfying");
    }

    @Test
    void fractionOfIntegersThatAreX_aliasesToFractionSatisfying() {
        // P-b72c-313: "fraction of integers that are prime" / "... that are happy".
        assertThat(MathToolSuite.normalizeMetric("fraction of integers that are prime"))
                .isEqualTo("fraction_satisfying");
        assertThat(MathToolSuite.normalizeMetric("fraction of integers that are happy"))
                .isEqualTo("fraction_satisfying");
    }

    @Test
    void proportionAndSatisfyingPhrasings_aliasToFractionSatisfying() {
        assertThat(MathToolSuite.normalizeMetric("proportion of the window"))
                .isEqualTo("fraction_satisfying");
        assertThat(MathToolSuite.normalizeMetric("count satisfying the predicate"))
                .isEqualTo("fraction_satisfying");
    }

    // ── Canonical tokens and the prior alias table still pass through ────────

    @Test
    void canonicalTokensUnchanged() {
        assertThat(MathToolSuite.normalizeMetric("fraction_satisfying")).isEqualTo("fraction_satisfying");
        assertThat(MathToolSuite.normalizeMetric("mean_of:sigmaSum")).isEqualTo("mean_of:sigmaSum");
        assertThat(MathToolSuite.normalizeMetric("max_of:collatzSteps")).isEqualTo("max_of:collatzSteps");
    }

    @Test
    void priorCollatzAndSigmaAliasesStillWork() {
        assertThat(MathToolSuite.normalizeMetric("mean collatz steps")).isEqualTo("mean_of:collatzSteps");
        assertThat(MathToolSuite.normalizeMetric("sigma sum")).isEqualTo("mean_of:sigmaSum");
    }

    @Test
    void blankDefaultsToFractionSatisfying() {
        assertThat(MathToolSuite.normalizeMetric("")).isEqualTo("fraction_satisfying");
        assertThat(MathToolSuite.normalizeMetric(null)).isEqualTo("fraction_satisfying");
    }

    // ── Genuinely-unsupported metrics must STILL reject (pass through verbatim) ─

    @Test
    void unsupportedMetricsPassThroughForValidSetRejection() {
        // mean_omega and mean_divisor_count are not phrasing errors — the tool
        // has no such statistic. They must NOT be silently aliased; they fall
        // through unchanged so runPredicateScan's valid-set check rejects them.
        assertThat(MathToolSuite.normalizeMetric("mean_omega")).isEqualTo("mean_omega");
        assertThat(MathToolSuite.normalizeMetric("mean_divisor_count")).isEqualTo("mean_divisor_count");
    }
}
