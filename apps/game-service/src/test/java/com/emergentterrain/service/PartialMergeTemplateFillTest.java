package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 G1 — pins the partial-merge template-fill helper that recovers
 * incomplete first-pass NarrationResults instead of skipping the critique
 * stage. Without this recovery, ~30% of refresh #1-#12 papers were stamped
 * critique:skip:incomplete_firstpass and lost their refinement work.
 */
class PartialMergeTemplateFillTest {

    @Test
    void fillsAllThreeNullFieldsFromTemplates() {
        NarrationResult firstPass = narrationWithNulls(null, null, null);
        NarrationResult merged = ResearchNarrationService.fillFromTemplates(
                draft("templ-h", "raw-f", "templ-c"), firstPass);
        assertThat(merged).isNotNull();
        assertThat(merged.hypothesis()).isEqualTo("templ-h");
        assertThat(merged.findings()).isEqualTo("raw-f");
        assertThat(merged.conclusion()).isEqualTo("templ-c");
    }

    @Test
    void fillsOnlyTheNullFieldAndPreservesOthers() {
        NarrationResult firstPass = narrationWithNulls("LLM hypothesis", null, "LLM conclusion");
        NarrationResult merged = ResearchNarrationService.fillFromTemplates(
                draft("templ-h", "raw-f", "templ-c"), firstPass);
        assertThat(merged).isNotNull();
        assertThat(merged.hypothesis()).isEqualTo("LLM hypothesis");
        assertThat(merged.findings()).isEqualTo("raw-f");
        assertThat(merged.conclusion()).isEqualTo("LLM conclusion");
    }

    @Test
    void preservesAuxiliaryFieldsOnMerge() {
        NarrationResult firstPass = new NarrationResult(
                null, "LLM findings", "LLM conclusion",
                null, "llama-3.3-70b-versatile",
                0.83, List.of("P-prior-1"),
                "fp:llama:ok", List.of(), List.of(),
                "narration_primary", "v3",
                "LLM discussion paragraph", "LLM related-work paragraph");
        NarrationResult merged = ResearchNarrationService.fillFromTemplates(
                draft("templ-h", "raw-f", "templ-c"), firstPass);
        assertThat(merged).isNotNull();
        assertThat(merged.hypothesis()).isEqualTo("templ-h");
        assertThat(merged.aiModel()).isEqualTo("llama-3.3-70b-versatile");
        assertThat(merged.confidenceScore()).isEqualTo(0.83);
        assertThat(merged.citedPaperIds()).containsExactly("P-prior-1");
        assertThat(merged.templateVersion()).isEqualTo("v3");
        assertThat(merged.discussion()).isEqualTo("LLM discussion paragraph");
        assertThat(merged.relatedWork()).isEqualTo("LLM related-work paragraph");
    }

    @Test
    void returnsNullWhenTemplatesCannotCloseAllGaps() {
        // findings null on firstPass AND rawFindings null on draft → unrecoverable.
        NarrationResult firstPass = narrationWithNulls("LLM hypothesis", null, "LLM conclusion");
        NarrationResult merged = ResearchNarrationService.fillFromTemplates(
                draft("templ-h", null, "templ-c"), firstPass);
        assertThat(merged).isNull();
    }

    @Test
    void returnsNullWhenAllTemplatesAndFirstPassAreNull() {
        NarrationResult firstPass = narrationWithNulls(null, null, null);
        NarrationResult merged = ResearchNarrationService.fillFromTemplates(
                draft(null, null, null), firstPass);
        assertThat(merged).isNull();
    }

    private static NarrationResult narrationWithNulls(String h, String f, String c) {
        return new NarrationResult(h, f, c, null, "test-model",
                0.5, List.of(), null, List.of(), List.of(),
                null, null, null, null);
    }

    private static PaperDraft draft(String templateHypothesis, String rawFindings, String templateConclusion) {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Collatz Stopping Time",
                "Test paper", rawFindings, templateHypothesis, templateConclusion,
                "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
