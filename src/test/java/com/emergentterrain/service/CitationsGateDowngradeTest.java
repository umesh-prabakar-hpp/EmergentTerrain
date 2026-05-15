package com.emergentterrain.service;

import com.emergentterrain.research.CitationValidator;
import com.emergentterrain.research.NarrationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 C5a — tests for {@link ResearchNarrationService#downgradeNarrationToNull}.
 *
 * <p>The downgrade transform preserves the hypothesis and the persisted trace
 * but rewrites findings + conclusion to an honest NULL_RESULT preamble naming
 * the validator's specific issues. Verifies the preamble shape and that all
 * other fields survive the rewrite.
 */
class CitationsGateDowngradeTest {

    private static NarrationResult buildNarration() {
        NarrationResult.NumericClaim claim =
                new NarrationResult.NumericClaim(9471, "K", "call_2", "max-T", "findings:1");
        return new NarrationResult(
                "Wien's peak should hold beyond the swept range.",
                Map.of(1, "Result extended to 9471 K [call_2] beyond the swept range."),
                "Confirmed at 9471 K [call_2].",
                null, "test-model", 0.65, List.of("P-a328-379"),
                "prof:test:ok",
                List.of(),
                List.of(),
                "test-id", "test-version",
                "discussion text", "related work text",
                List.of("overclaiming"),
                List.of(),
                null,
                List.of(claim));
    }

    @Test
    void downgrade_writesHonestPreamble() {
        CitationValidator.CitationValidationResult cv =
                new CitationValidator.CitationValidationResult(
                        0, 0, 1,
                        List.of("call_2 result does not contain value 9471 (claimed in findings:1)"));
        NarrationResult downgraded =
                ResearchNarrationService.downgradeNarrationToNull(buildNarration(), cv);

        assertThat(downgraded.findings())
                .startsWith("NULL_RESULT — citation validator flagged 1 numeric value");
        assertThat(downgraded.findings())
                .contains("hypothesis remains untested");
        assertThat(downgraded.findings())
                .contains("9471");
    }

    @Test
    void downgrade_rewritesConclusion() {
        CitationValidator.CitationValidationResult cv =
                new CitationValidator.CitationValidationResult(0, 0, 1, List.of("issue"));
        NarrationResult downgraded =
                ResearchNarrationService.downgradeNarrationToNull(buildNarration(), cv);

        assertThat(downgraded.conclusion()).contains("not verified");
        assertThat(downgraded.conclusion()).contains("null result");
    }

    @Test
    void downgrade_preservesHypothesisAndProvenance() {
        CitationValidator.CitationValidationResult cv =
                new CitationValidator.CitationValidationResult(0, 1, 0, List.of("issue"));
        NarrationResult original = buildNarration();
        NarrationResult downgraded =
                ResearchNarrationService.downgradeNarrationToNull(original, cv);

        assertThat(downgraded.hypothesis()).isEqualTo(original.hypothesis());
        assertThat(downgraded.citedPaperIds()).isEqualTo(original.citedPaperIds());
        assertThat(downgraded.aiModel()).isEqualTo(original.aiModel());
        assertThat(downgraded.templateId()).isEqualTo(original.templateId());
        assertThat(downgraded.templateVersion()).isEqualTo(original.templateVersion());
        assertThat(downgraded.numericClaims()).isEqualTo(original.numericClaims());
        assertThat(downgraded.auditGapDimensions()).isEqualTo(original.auditGapDimensions());
    }

    @Test
    void downgrade_includesFirstThreeIssuesInPreamble() {
        // When there are many issues, only the first 3 appear in the preamble
        // (rest are in logs). Keeps the published prose concise.
        CitationValidator.CitationValidationResult cv =
                new CitationValidator.CitationValidationResult(0, 0, 5, List.of(
                        "issue one fabricated 100",
                        "issue two fabricated 200",
                        "issue three fabricated 300",
                        "issue four fabricated 400",
                        "issue five fabricated 500"));
        NarrationResult downgraded =
                ResearchNarrationService.downgradeNarrationToNull(buildNarration(), cv);

        assertThat(downgraded.findings()).contains("issue one");
        assertThat(downgraded.findings()).contains("issue two");
        assertThat(downgraded.findings()).contains("issue three");
        assertThat(downgraded.findings()).doesNotContain("issue four");
        assertThat(downgraded.findings()).doesNotContain("issue five");
    }

    @Test
    void downgrade_pluralisesCorrectly() {
        CitationValidator.CitationValidationResult single =
                new CitationValidator.CitationValidationResult(1, 0, 0, List.of("x"));
        CitationValidator.CitationValidationResult multi =
                new CitationValidator.CitationValidationResult(0, 2, 1, List.of("x", "y", "z"));

        NarrationResult dSingle = ResearchNarrationService.downgradeNarrationToNull(
                buildNarration(), single);
        NarrationResult dMulti = ResearchNarrationService.downgradeNarrationToNull(
                buildNarration(), multi);

        assertThat(dSingle.findings()).contains("1 numeric value");
        assertThat(dSingle.findings()).doesNotContain("1 numeric values");
        assertThat(dMulti.findings()).contains("3 numeric values");
    }
}
