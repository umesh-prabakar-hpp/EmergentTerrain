package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-05 rec #4 — span-decomposition guidance for run_predicate_scan. The
 * recurring failure (7/7 predicate papers across four batches) is a wide-range
 * request rejected by the span cap, followed by prose claiming the never-scanned
 * range anyway (P-b72c-131 [100,1900), P-b72c-124 [1000,1400) — both NULL'd as
 * scope overreach). Both the advertisement and the rejection message must teach
 * the repair path: several ADJACENT ≤500 scans, never a claim over an unscanned
 * range. Mockito-free per the dev-shell JDK25 constraint (null router; neither
 * toolsJson nor the invalid-span path touches it).
 */
class MathToolSuitePredicateScanGuidanceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private MathToolSuite suite;

    @BeforeEach
    void setUp() {
        suite = new MathToolSuite((LlmRouter) null, M);
    }

    @Test
    void toolDescriptionTeachesDecomposition() {
        String json = suite.toolsJson(true);
        assertThat(json).contains("ADJACENT sub-ranges");
        assertThat(json).contains("never describe results over a range you did not scan");
    }

    @Test
    void invalidSpanRejectionNamesTheRepairPath() {
        String result = suite.buildExecutor(null, new MathToolSuite.ToolMetrics()).apply("""
                {"name":"run_predicate_scan","arguments":{
                  "predicate":"isPrime AND isHappy","range_start":100,"range_end":1900,
                  "metric":"fraction_satisfying","baseline":0.025,
                  "hypothesis":"density over a wide range"}}
                """);
        assertThat(result).startsWith("Invalid predicate scan:");   // stays excluded from the
        // executed-range union (NumericalConsistencyValidator's leading-"Invalid" test)
        assertThat(result).contains("ADJACENT sub-ranges");
        assertThat(result).contains("do NOT describe results over a range you did not scan");
    }
}
