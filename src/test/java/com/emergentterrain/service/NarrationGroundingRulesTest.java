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
}
