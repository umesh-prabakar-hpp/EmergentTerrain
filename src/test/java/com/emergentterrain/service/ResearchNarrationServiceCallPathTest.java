package com.emergentterrain.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.emergentterrain.research.PanelOutcome;
import com.emergentterrain.research.PanelReview;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 F7.4 — call-path stamp format tests for the multi-reviewer panel.
 * Unit-tests the static {@code buildPanelCallPathStamp} helper directly so the
 * exact stamp format is pinned without standing up a full ResearchNarrationService.
 */
class ResearchNarrationServiceCallPathTest {

    @Test
    void fullPanelStampsAgreementOnly() {
        // 4 reviewers responded out of 4 configured — full panel.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(4, 4, 0.87);
        assertThat(stamp).isEqualTo("panel:n=4:agreement=0.87");
        assertThat(stamp).doesNotContain("degraded");
    }

    @Test
    void partialPanelStampsDegradedWithConfiguredCount() {
        // 2 of 4 responded — degraded.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(2, 4, 0.75);
        assertThat(stamp).isEqualTo("panel:n=2:degraded:from=4:agreement=0.75");
    }

    @Test
    void singleReviewerOutOfFourIsDegraded() {
        // 1 of 4 — operators can grep `degraded` to find these.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(1, 4, 1.0);
        assertThat(stamp).isEqualTo("panel:n=1:degraded:from=4:agreement=1.00");
    }

    @Test
    void singleConfiguredReviewerIsFullNotDegraded() {
        // panelSize=1 (only one provider opted in); 1 responded — full, not degraded.
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(1, 1, 1.0);
        assertThat(stamp).isEqualTo("panel:n=1:agreement=1.00");
        assertThat(stamp).doesNotContain("degraded");
    }

    @Test
    void agreementScoreFormattedToTwoDecimalPlaces() {
        // 0.876543 must render as "0.88" (not "0.88...").
        String stamp = ResearchNarrationService.buildPanelCallPathStamp(3, 3, 0.876543);
        assertThat(stamp).isEqualTo("panel:n=3:agreement=0.88");
    }

    // ---- Phase 14 F2b: professor-exception zero-successes suppression ----

    @Test
    void f2_zeroSuccessesAndMultiBlockSuppressesOnException() {
        // P-505-class signature: 0 successful tool calls, multi-block firstPass.
        // Suppress instead of falling through to firstPass-fallback so the
        // un-validated fabricated prose never reaches publishPaper.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 5)).isTrue();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 2)).isTrue();
    }

    @Test
    void f2_zeroSuccessesAndSingleBlockDoesNotSuppress() {
        // Single-block firstPass on exception is structurally indistinguishable
        // from a legitimate single-computation narration that lost its trace
        // to the exception; falling through to firstPass-fallback is safer
        // than suppressing.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 1)).isFalse();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(0, 0)).isFalse();
    }

    @Test
    void f2_nonZeroSuccessesNeverSuppresses() {
        // The metrics gate at line 492 already handles the case where the
        // professor produced *some* successful calls. F2b's check fires
        // ONLY when no successes accumulated before the exception.
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(1, 5)).isFalse();
        assertThat(ResearchNarrationService.shouldSuppressOnProfessorException(2, 5)).isFalse();
    }

    // ---- Phase 17 D3-ext: gate-skipped-professor quantitative-claim detector ----

    @Test
    void d3ext_p559FabricatedPendulumAngleDetected() {
        // Real conclusion text from P-a328-559 (gate:resources:professor case):
        // "the 1% error threshold occurs at about 25°" — no computation backs it.
        String conclusion = "The pre-registered prediction that the critical angle "
                + "θ_c would be less than 20° was not confirmed, as the 1% error "
                + "threshold occurs at about 25°.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_p568FabricatedBuoyantForceDetected() {
        // Real conclusion text from P-a328-568:
        // "maximum buoyant force of 1480 kN at a volume of 25.8 m³ and a
        //  liquid density of 5844 kg/m³"
        String conclusion = "The pre-registered prediction was supported by the "
                + "computation results, which showed a maximum buoyant force of "
                + "1480 kN at a volume of 25.8 m³ and a liquid density of "
                + "5844 kg/m³.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_p575InternalContradictionDetected() {
        // Real conclusion text from P-a328-575 (NULL_RESULT but claims "confirmed"):
        // "worst deviation being approximately 0.0005"
        String conclusion = "The pre-registered prediction that the ratio of the "
                + "truncated ζ-sum to the Euler product would remain within 5% of "
                + "unity was confirmed, with the worst deviation being "
                + "approximately 0.0005.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isTrue();
    }

    @Test
    void d3ext_honestQualitativeConclusionPasses() {
        // The intended honest shape for a gate-killed paper: no specific number.
        String conclusion = "The hypothesis remains untested in this submission. "
                + "The professor stage was skipped due to resource pressure and "
                + "no quantitative claim is made.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isFalse();
    }

    @Test
    void d3ext_callCitationsAloneDoNotTrigger() {
        // A conclusion that references prior calls but makes no quantitative
        // claim of its own. The [call_N] tokens carry digits we strip first.
        String conclusion = "The findings reported in [call_1] and [call_2] are "
                + "consistent with the hypothesis. See Computation 1 and "
                + "Computation 2 for details.";
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(conclusion)).isFalse();
    }

    @Test
    void d3ext_nullAndBlankPass() {
        // Null and blank are both "no quantitative claim". Suppression should
        // NOT fire on these — they're handled upstream by the
        // fillFromTemplates / critique:skip:incomplete_firstpass path.
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(null)).isFalse();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim("")).isFalse();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim("   \n  ")).isFalse();
    }

    @Test
    void d3ext_digitOutsideStrippedRefsTriggers() {
        // A bare digit anywhere outside [call_N] / "Computation N" should
        // fire. The regex is intentionally loose — any specific number in a
        // gate-killed paper is suspect.
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(
                "[call_1] supports the result at threshold 0.05.")).isTrue();
        assertThat(ResearchNarrationService.conclusionHasQuantitativeClaim(
                "Computation 1 yields a mean of 42.")).isTrue();
    }

    // ---- Phase 17 P2: panel-revision feedback gate + prompt builder ----

    private static PanelOutcome panelOutcome(int contradictionCount, int recommendationCount) {
        List<PanelReview.Contradiction> contradictions = new java.util.ArrayList<>();
        for (int i = 0; i < contradictionCount; i++) {
            contradictions.add(new PanelReview.Contradiction(i, "prose claims X but trace shows Y"));
        }
        List<String> recs = new java.util.ArrayList<>();
        for (int i = 0; i < recommendationCount; i++) {
            recs.add("Recommendation " + i);
        }
        // Build a fake panel with a single review so isEmpty() returns false.
        PanelReview fake = new PanelReview(
                "fake-reviewer",
                new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                List.of(), List.of(),
                contradictions, recs);
        return new PanelOutcome(
                new PanelReview.Grades(0.5, 0.5, 0.5, 0.5),
                0.8,
                List.of(), List.of(),
                contradictions, recs,
                List.of(fake));
    }

    @Test
    void panelRevision_actionableWhenOneContradiction() {
        assertThat(ResearchNarrationService.isPanelFeedbackActionable(panelOutcome(1, 0))).isTrue();
    }

    @Test
    void panelRevision_actionableWhenTwoRecommendations() {
        assertThat(ResearchNarrationService.isPanelFeedbackActionable(panelOutcome(0, 2))).isTrue();
    }

    @Test
    void panelRevision_notActionableWhenSingleRecommendation() {
        // A lone "could be clearer" recommendation is too vague to act on
        // — the revision would likely no-op or fabricate.
        assertThat(ResearchNarrationService.isPanelFeedbackActionable(panelOutcome(0, 1))).isFalse();
    }

    @Test
    void panelRevision_notActionableOnEmptyPanel() {
        assertThat(ResearchNarrationService.isPanelFeedbackActionable(PanelOutcome.empty())).isFalse();
        assertThat(ResearchNarrationService.isPanelFeedbackActionable(null)).isFalse();
    }

    @Test
    void panelRevision_noChangeDetectsIdenticalText() {
        assertThat(ResearchNarrationService.isNoChangeRevision(
                "Found a peak of 0.788.", "Found a peak of 0.788.",
                "Peak found.", "Peak found.")).isTrue();
    }

    @Test
    void panelRevision_noChangeIgnoresWhitespace() {
        assertThat(ResearchNarrationService.isNoChangeRevision(
                "Found  a   peak.",         "Found a peak.",
                "  Peak found.\n\n",        "Peak found.")).isTrue();
    }

    @Test
    void panelRevision_changeDetectedOnFindingsDiff() {
        assertThat(ResearchNarrationService.isNoChangeRevision(
                "Found a peak of 0.793.", "Found a peak of 0.788.",
                "Peak found.",            "Peak found.")).isFalse();
    }

    @Test
    void panelRevision_changeDetectedOnConclusionDiff() {
        assertThat(ResearchNarrationService.isNoChangeRevision(
                "Findings.",  "Findings.",
                "Peak at 0.793.", "Peak at 0.788.")).isFalse();
    }

    @Test
    void panelRevision_promptIncludesPinnedHypothesis() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hypothesis under test.",
                "Findings prose.",
                "Conclusion prose.",
                null, "test-model", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(2, 3));
        assertThat(prompt).contains("Hypothesis under test.");
        assertThat(prompt).contains("pin — do not change");
    }

    @Test
    void panelRevision_promptListsContradictions() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(2, 0));
        // Both contradictions show up with their trace index.
        assertThat(prompt).contains("PANEL CONSENSUS CONTRADICTIONS");
        assertThat(prompt).contains("At tool call 0:");
        assertThat(prompt).contains("At tool call 1:");
    }

    @Test
    void panelRevision_promptListsRecommendations() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(0, 3));
        assertThat(prompt).contains("PANEL RECOMMENDATIONS");
        assertThat(prompt).contains("Recommendation 0");
        assertThat(prompt).contains("Recommendation 1");
        assertThat(prompt).contains("Recommendation 2");
    }

    @Test
    void panelRevision_promptStatesNoNewNumericsConstraint() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(1, 0));
        // The critical guardrail: no fabricated numbers.
        assertThat(prompt).contains("Do NOT introduce numeric values that are not in the original tool-call trace");
        assertThat(prompt).contains("Do NOT invent new tool calls");
    }

    @Test
    void panelRevision_promptRequestsExactJsonSchema() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(1, 0));
        assertThat(prompt).contains("Return ONLY valid JSON");
        assertThat(prompt).contains("\"hypothesis\"");
        assertThat(prompt).contains("\"findings\"");
        assertThat(prompt).contains("\"conclusion\"");
    }

    @Test
    void panelRevision_promptOmitsContradictionSectionWhenEmpty() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        // Only recommendations, no contradictions.
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(0, 2));
        assertThat(prompt).doesNotContain("PANEL CONSENSUS CONTRADICTIONS");
        assertThat(prompt).contains("PANEL RECOMMENDATIONS");
    }
}
