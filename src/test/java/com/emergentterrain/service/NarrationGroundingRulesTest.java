package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REVIEW_REPORT b72c4e10 §1 rec #4/#5 — flag-gated revision-stage grounding
 * rules. Mirrors the static, router-free testability of
 * {@code appendNoveltyPreservation} / {@code appendFirstPassMetricGuard}: the
 * append helpers and rule constants are package-private statics, so the gating
 * and the rule wording are verified without standing up Spring or a router.
 */
class NarrationGroundingRulesTest {

    private static final String BASE = "BASE PROMPT BODY";

    // ---- Rec 4: executed-scope grounding ----

    @Test
    void executedScopeRuleAppendedOnlyWhenEnabled() {
        assertThat(ResearchNarrationService.appendExecutedScopeGrounding(BASE, false))
                .isEqualTo(BASE);
        String on = ResearchNarrationService.appendExecutedScopeGrounding(BASE, true);
        assertThat(on).startsWith(BASE);
        assertThat(on).contains("EXECUTED-SCOPE GROUNDING");
    }

    @Test
    void executedScopeRuleBarsUnexecutedRangesAndSignFlips() {
        String rule = ResearchNarrationService.EXECUTED_SCOPE_RULE;
        // ranges constraint (the P-b72c-19 [100,500)/[700,1100) fabrication)
        assertThat(rule).contains("ranges or");
        assertThat(rule).contains("no call covered");
        assertThat(rule).contains("not tested");
        // exact-sign/magnitude (the +0.52-for-z=-0.523 flip)
        assertThat(rule).contains("z=-0.523");
        assertThat(rule).contains("do not flip a sign");
    }

    // ---- Rec 5: definition grounding ----

    @Test
    void definitionRuleAppendedOnlyWhenEnabled() {
        assertThat(ResearchNarrationService.appendDefinitionGrounding(BASE, false))
                .isEqualTo(BASE);
        String on = ResearchNarrationService.appendDefinitionGrounding(BASE, true);
        assertThat(on).startsWith(BASE);
        assertThat(on).contains("DEFINITION GROUNDING");
    }

    @Test
    void definitionRuleBindsToToolNormalizer() {
        String rule = ResearchNarrationService.DEFINITION_GROUNDING_RULE;
        assertThat(rule).contains("EXACTLY as the tools computed it");
        assertThat(rule).contains("sqrt(window_size)");
        assertThat(rule).contains("sqrt(window_start)");
        assertThat(rule).contains("do NOT");
    }

    // ---- both: independent gating composes ----

    @Test
    void helpersComposeAndGateIndependently() {
        String both = ResearchNarrationService.appendDefinitionGrounding(
                ResearchNarrationService.appendExecutedScopeGrounding(BASE, true), true);
        assertThat(both).contains("EXECUTED-SCOPE GROUNDING")
                .contains("DEFINITION GROUNDING");
        // neither rule introduces a JSON output field (schema-compat with the
        // pinned NARRATION_RESPONSE_SCHEMA additionalProperties:false).
        assertThat(ResearchNarrationService.EXECUTED_SCOPE_RULE).doesNotContain("\":");
        assertThat(ResearchNarrationService.DEFINITION_GROUNDING_RULE).doesNotContain("\":");
    }

    // ---- 2026-06-06 rec #1: dynamic executed-union rule ----

    @Test
    void executedUnionNamesTheActualCoverage() {
        // P-b72c-249's shape: scans covered [400,800); prose claimed [800,1200).
        // The dynamic rule must NAME the union so the reviser can't misread it.
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_predicate_scan",
                        "{\"range_start\":400,\"range_end\":800}", "fraction = 0.025", 0, "c1"),
                new com.emergentterrain.llm.ToolInvocation("verify_conjecture",
                        "{\"window_start\":800,\"window_size\":100,\"num_windows\":4}",
                        "HELD across 4 window(s)", 1, "c2"));
        String out = ResearchNarrationService.appendExecutedUnion(BASE, true, trace);
        assertThat(out).startsWith(BASE)
                .contains("EXECUTED COVERAGE")
                .contains("[400, 1200)")          // fused union, named exactly
                .contains("INSIDE this union");
    }

    @Test
    void executedUnionGatesAndDegradesQuietly() {
        assertThat(ResearchNarrationService.appendExecutedUnion(BASE, false,
                java.util.List.of())).isEqualTo(BASE);
        assertThat(ResearchNarrationService.appendExecutedUnion(BASE, true, null))
                .isEqualTo(BASE);   // empty union → unchanged
        // No JSON output field introduced.
        assertThat(ResearchNarrationService.appendExecutedUnion(BASE, true,
                java.util.List.of(new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan", "{\"range_start\":1,\"range_end\":5}",
                        "ok", 0, "c1"))))
                .doesNotContain("\":");
    }

    @Test
    void pivotFramingRule_appendedIffEnabled() {
        // 2026-07-05 (P-b72c-77627774) — refuted prereg + established positive alternative must
        // be framed as a pivot with a positive finding, not stamped null-result.
        // 2026-07-05 eve (P-b72c-77627790) — the rule also covers the inconclusive-measurement
        // cousin: a measured-but-unsettled quantity is reported AS the measurement with its
        // spread, never stamped null.
        assertThat(ResearchNarrationService.appendPivotFraming(BASE, true))
                .startsWith(BASE)
                .contains("PIVOT RULE")
                .contains("NOT a null result")
                .contains("INCONCLUSIVE MEASUREMENT")
                .contains("neither confirmed nor excluded");
        assertThat(ResearchNarrationService.appendPivotFraming(BASE, false)).isEqualTo(BASE);
    }
}
