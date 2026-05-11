package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4c Piece 2 gate 3: pin the {@code narrationTemplateId / version} round-trip
 * from {@link NarrationResult} → {@link ResearchPaper}. Without this seam, Piece 3
 * has no way to attribute a replication outcome back to the prompt variant that
 * produced the paper.
 *
 * The wiring under test lives in {@code UniversityService.publishPaper}, which
 * calls {@link ResearchPaper#setNarrationTemplateId} with the value from the
 * narration result. Reproducing that step here without spinning up the
 * UniversityService dependency graph isolates the contract to a unit test.
 */
class PromptTemplateProvenanceTest {

    @Test
    void researchPaperRoundTripsTemplateProvenance() {
        ResearchPaper paper = newDomainPaper("P-test-1");

        paper.setNarrationTemplateId(PromptTemplateIds.NARRATION_PRIMARY_ID);
        paper.setNarrationTemplateVersion(PromptTemplateIds.NARRATION_PRIMARY_VERSION);

        assertThat(paper.narrationTemplateId()).isEqualTo("narration.primary");
        assertThat(paper.narrationTemplateVersion()).isEqualTo("v1");
    }

    @Test
    void legacyPaperReadsBackNullProvenance() {
        ResearchPaper paper = newDomainPaper("P-test-legacy");

        // No setNarrationTemplate* calls — old rows persisted before the column
        // existed should round-trip as null without throwing.
        assertThat(paper.narrationTemplateId()).isNull();
        assertThat(paper.narrationTemplateVersion()).isNull();
    }

    @Test
    void narrationResultWithProvenanceCarriesItOnTheRecord() {
        NarrationResult base = new NarrationResult(
                "h", "f", "c", null, "test-model",
                0.5, List.of(), "path", List.of(), List.of());

        NarrationResult stamped = base.withTemplateProvenance(
                PromptTemplateIds.NARRATION_PRIMARY_ID,
                PromptTemplateIds.NARRATION_PRIMARY_VERSION);

        assertThat(stamped.templateId()).isEqualTo("narration.primary");
        assertThat(stamped.templateVersion()).isEqualTo("v1");
        // All other fields preserved.
        assertThat(stamped.hypothesis()).isEqualTo("h");
        assertThat(stamped.findings()).isEqualTo("f");
        assertThat(stamped.callPath()).isEqualTo("path");
    }

    @Test
    void backwardCompatibleConstructorsLeaveProvenanceNull() {
        // 7-arg legacy callers (e.g. fallback suppression results) get null provenance.
        NarrationResult sevenArg = new NarrationResult(
                null, null, null, null, null, 0.5, List.of());
        assertThat(sevenArg.templateId()).isNull();
        assertThat(sevenArg.templateVersion()).isNull();

        // 9-arg path mirrors the legacy parser entry point — also null provenance.
        NarrationResult nineArg = new NarrationResult(
                "h", "f", "c", null, "model", 0.7, List.of(), "path", List.of());
        assertThat(nineArg.templateId()).isNull();
        assertThat(nineArg.templateVersion()).isNull();
    }

    @Test
    void everyDeclaredTemplateIdHasMatchingVersionConstant() {
        // Sanity: the constants holder pairs every id with a version. A typo
        // (e.g. only adding NARRATION_FOO_ID without NARRATION_FOO_VERSION)
        // would silently break Piece 3 attribution. This is a one-shot guard.
        record Pair(String id, String version) {}
        List<Pair> pairs = List.of(
                new Pair(PromptTemplateIds.NARRATION_PRIMARY_ID,            PromptTemplateIds.NARRATION_PRIMARY_VERSION),
                new Pair(PromptTemplateIds.NARRATION_REFINEMENT_ID,         PromptTemplateIds.NARRATION_REFINEMENT_VERSION),
                new Pair(PromptTemplateIds.NARRATION_CRITIQUE_ID,           PromptTemplateIds.NARRATION_CRITIQUE_VERSION),
                new Pair(PromptTemplateIds.NARRATION_AUDIT_ID,              PromptTemplateIds.NARRATION_AUDIT_VERSION),
                new Pair(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,   PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION),
                new Pair(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,     PromptTemplateIds.NARRATION_PROFESSOR_USER_VERSION),
                new Pair(PromptTemplateIds.NARRATION_REVISION_ID,           PromptTemplateIds.NARRATION_REVISION_VERSION),
                new Pair(PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_ID,PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_VERSION),
                new Pair(PromptTemplateIds.FORMULATION_MAIN_ID,             PromptTemplateIds.FORMULATION_MAIN_VERSION),
                new Pair(PromptTemplateIds.FORMULATION_NOVELTY_ID,          PromptTemplateIds.FORMULATION_NOVELTY_VERSION),
                new Pair(PromptTemplateIds.MENTORING_GENOME_ID,             PromptTemplateIds.MENTORING_GENOME_VERSION));

        for (Pair p : pairs) {
            assertThat(p.id()).as("template id").isNotBlank();
            assertThat(p.version()).as("template version for " + p.id()).isNotBlank();
        }
    }

    private static ResearchPaper newDomainPaper(String paperId) {
        return new ResearchPaper(
                paperId, UUID.randomUUID(), "bot-1", "MATHEMATICS", "primes",
                "title", "hypothesis", "THEORETICAL", "findings", "conclusion",
                List.of("rev-1", "rev-2"), 0.42, "PUBLISHED",
                100L, Instant.now(), 0, 0.0,
                "test-model", "test:path");
    }
}
