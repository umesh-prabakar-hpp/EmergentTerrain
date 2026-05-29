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
    void panelRevision_promptStatesCitationValidityConstraint() {
        // rec #4 — the revision prompt must spell out that citations may only
        // reference real, non-failed calls within the trace, so the revision
        // doesn't reintroduce hallucinated [call_N] indices or cite errored calls
        // (P-a328-993/994 pattern).
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(1, 0));
        assertThat(prompt).contains("Never cite a call index higher than the number of calls in the trace");
        assertThat(prompt).contains("never cite a call that errored or was REJECTED");
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

    // ---- rec #4 follow-up: revision-prompt [call_N] trace grounding ----

    private static com.emergentterrain.llm.ToolInvocation inv(
            String fn, String args, String result, int round, String callId) {
        return new com.emergentterrain.llm.ToolInvocation(fn, args, result, round, callId);
    }

    @Test
    void traceBlock_emptyForNullOrEmptyTrace() {
        assertThat(ResearchNarrationService.renderCitableTraceBlock(null)).isEmpty();
        assertThat(ResearchNarrationService.renderCitableTraceBlock(List.of())).isEmpty();
    }

    @Test
    void traceBlock_listsRealCallIdsAndStatesCeiling() {
        String block = ResearchNarrationService.renderCitableTraceBlock(List.of(
                inv("run_param_sweep", "{\"param1\":3}", "{\"deviation\":0.29}", 1, "call_1"),
                inv("run_symbolic", "{\"expr\":\"b/T\"}", "{\"value\":1.0}", 2, "call_2")));
        assertThat(block).contains("[call_1] run_param_sweep");
        assertThat(block).contains("[call_2] run_symbolic");
        // The ceiling is stated explicitly so the reviser cannot over-run it.
        assertThat(block).contains("Valid citation indices: call_1 … call_2");
        assertThat(block).contains("Never cite an index above call_2");
    }

    @Test
    void traceBlock_flagsErroredAndRejectedCallsDoNotCite() {
        // P-a328-994 cited the FAILED call_1/5 as data — failed calls must be
        // rendered (they occupy a positional index) but explicitly DO-NOT-CITE,
        // and their result body must NOT be shown as a citable value.
        String block = ResearchNarrationService.renderCitableTraceBlock(List.of(
                inv("run_sandboxed_code", "{}", "{\"error\":true,\"loop limit\":1}", 1, "call_1"),
                inv("run_symbolic", "{}", "REJECTED by safety: unbalanced parens", 2, "call_2"),
                inv("run_param_sweep", "{}", "{\"deviation\":1.18}", 3, "call_3")));
        assertThat(block).contains("[call_1] run_sandboxed_code({}) → FAILED");
        assertThat(block).contains("[call_2] run_symbolic({}) → FAILED");
        assertThat(block).doesNotContain("loop limit");      // failed body not surfaced as data
        assertThat(block).contains("[call_3] run_param_sweep({}) → ");
        assertThat(block).contains("1.18");                  // good call's value IS surfaced
    }

    @Test
    void traceBlock_fallsBackToPositionalIdWhenCallIdNull() {
        // pre-Phase-11 traces have null callId; render the 1-based position.
        String block = ResearchNarrationService.renderCitableTraceBlock(List.of(
                inv("run_symbolic", "{}", "{\"value\":2}", 1, null)));
        assertThat(block).contains("[call_1] run_symbolic");
    }

    @Test
    void panelRevision_threeArgInjectsTraceBlock() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        String traceBlock = ResearchNarrationService.renderCitableTraceBlock(List.of(
                inv("run_param_sweep", "{}", "{\"deviation\":0.29}", 1, "call_1")));
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(
                n, panelOutcome(1, 0), traceBlock);
        assertThat(prompt).contains("TOOL-CALL TRACE — the ONLY valid [call_N] citations");
        assertThat(prompt).contains("[call_1] run_param_sweep");
        // The block precedes the OUTPUT FORMAT section.
        assertThat(prompt.indexOf("TOOL-CALL TRACE"))
                .isLessThan(prompt.indexOf("=== OUTPUT FORMAT ==="));
    }

    @Test
    void panelRevision_twoArgOmitsTraceBlock() {
        com.emergentterrain.research.NarrationResult n = new com.emergentterrain.research.NarrationResult(
                "Hyp.", "Find.", "Conc.", null, "m", 0.5, List.of());
        // Backward-compat: the 2-arg overload (and "" trace) emits no trace block.
        String prompt = ResearchNarrationService.buildPanelRevisionPrompt(n, panelOutcome(1, 0));
        assertThat(prompt).doesNotContain("TOOL-CALL TRACE");
    }

    // ---- Phase 17 panel-grade-floor demote gate ----

    private static PanelOutcome panelOutcomeWithGrades(
            double honesty, double completeness, double rigor, double novelty,
            int contradictionCount) {
        List<PanelReview.Contradiction> contradictions = new java.util.ArrayList<>();
        for (int i = 0; i < contradictionCount; i++) {
            contradictions.add(new PanelReview.Contradiction(i, "claim X but trace Y"));
        }
        PanelReview.Grades grades = new PanelReview.Grades(honesty, completeness, rigor, novelty);
        PanelReview fake = new PanelReview(
                "fake-reviewer", grades, List.of(), List.of(),
                contradictions, List.of());
        return new PanelOutcome(
                grades, 0.9,
                List.of(), List.of(),
                contradictions, List.of(),
                List.of(fake));
    }

    @Test
    void gradeFloor_p593CaseFires() {
        // Real grades from P-a328-593 (post-Phase-17 refresh #1):
        // honesty=0.10, completeness=0.00, rigor=0.10, novelty=0.00
        // 2 consensus contradictions on the 1147× unit error.
        PanelOutcome o = panelOutcomeWithGrades(0.10, 0.00, 0.10, 0.00, 2);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isTrue();
        // avg(h,c,r) = 0.0667 — well below 0.30
        assertThat(ResearchNarrationService.honestyCompletenessRigorAverage(o))
                .isCloseTo(0.0667, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void gradeFloor_p595BasquinDoesNotFire() {
        // Real grades from P-595 (highest in this batch):
        // honesty=1.00, completeness=1.00, rigor=1.00, novelty=0.00
        // No contradictions. Don't fire — textbook topic, valid math.
        PanelOutcome o = panelOutcomeWithGrades(1.00, 1.00, 1.00, 0.00, 0);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isFalse();
        // avg(h,c,r) = 1.00 — novelty correctly excluded
        assertThat(ResearchNarrationService.honestyCompletenessRigorAverage(o))
                .isCloseTo(1.00, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void gradeFloor_lowGradesAloneDoNotFire() {
        // Catastrophically low grades but NO contradictions — could be a
        // noisy or harsh panel; don't demote without specific evidence.
        PanelOutcome o = panelOutcomeWithGrades(0.05, 0.05, 0.05, 0.00, 0);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isFalse();
    }

    @Test
    void gradeFloor_contradictionsAloneDoNotFire() {
        // One contradiction on an otherwise good paper — the panel-revision
        // pass can fix this; don't downgrade just because one issue surfaced.
        PanelOutcome o = panelOutcomeWithGrades(0.80, 0.80, 0.80, 0.50, 1);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isFalse();
    }

    @Test
    void gradeFloor_atThresholdDoesNotFire() {
        // Strict-less-than semantics: at exactly threshold, don't fire.
        PanelOutcome o = panelOutcomeWithGrades(0.30, 0.30, 0.30, 0.00, 1);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isFalse();
    }

    @Test
    void gradeFloor_justBelowThresholdFires() {
        // Average h,c,r = 0.27 (below 0.30) + ≥1 contradiction → fire.
        PanelOutcome o = panelOutcomeWithGrades(0.27, 0.27, 0.27, 0.00, 1);
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isTrue();
    }

    @Test
    void gradeFloor_noveltyExcluded() {
        // Novelty 0 must not pull the avg down when h, c, r are healthy.
        // P-a328-595 shape: h=1.0, c=1.0, r=1.0, novelty=0.0, 5 contradictions.
        PanelOutcome o = panelOutcomeWithGrades(1.00, 1.00, 1.00, 0.00, 5);
        // Even with 5 contradictions, avg(h,c,r)=1.00 ≥ 0.30 so don't fire.
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isFalse();
    }

    @Test
    void gradeFloor_emptyOutcomeDoesNotFire() {
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(PanelOutcome.empty(), 0.30))
                .isFalse();
    }

    @Test
    void gradeFloor_nullOutcomeDoesNotFire() {
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(null, 0.30)).isFalse();
    }

    @Test
    void gradeFloor_averageIgnoresNovelty() {
        // honesty=0.6, completeness=0.6, rigor=0.6, novelty=0.0
        // Including novelty: avg = 0.45. Excluding (the gate's contract): avg = 0.60.
        PanelOutcome o = panelOutcomeWithGrades(0.60, 0.60, 0.60, 0.00, 0);
        assertThat(ResearchNarrationService.honestyCompletenessRigorAverage(o))
                .isCloseTo(0.60, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void gradeFloor_thresholdConfigurable() {
        // Same outcome, two thresholds — the gate must respect the parameter.
        PanelOutcome o = panelOutcomeWithGrades(0.25, 0.25, 0.25, 0.00, 1);
        // At threshold 0.20, avg=0.25 ≥ 0.20 → don't fire.
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.20)).isFalse();
        // At threshold 0.30, avg=0.25 < 0.30 → fire.
        assertThat(ResearchNarrationService.shouldDemoteByPanelGradeFloor(o, 0.30)).isTrue();
    }
}
