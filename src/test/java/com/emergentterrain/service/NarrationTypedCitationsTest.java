package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.RelationshipType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2b — parser tests for the optional {@code cited_papers} JSON shape.
 * When the LLM emits the richer per-citation form, the parser populates
 * {@link NarrationResult#typedCitations()}; when it only emits the legacy
 * flat {@code cited_paper_ids}, typedCitations stays empty and the publish
 * path defaults each edge to EXTENDS.
 */
class NarrationTypedCitationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTypedCitationsArray() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c",
                 "confidenceScore":0.7,
                 "cited_papers":[
                    {"id":"P-aaaa-1","type":"SUPPORTS","confidence":0.85},
                    {"id":"P-bbbb-2","type":"CONTRADICTS","confidence":0.6}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations()).hasSize(2);
        assertThat(r.typedCitations().get(0).paperId()).isEqualTo("P-aaaa-1");
        assertThat(r.typedCitations().get(0).type()).isEqualTo(RelationshipType.SUPPORTS);
        assertThat(r.typedCitations().get(0).confidence()).isEqualTo(0.85);
        assertThat(r.typedCitations().get(1).type()).isEqualTo(RelationshipType.CONTRADICTS);

        // Flat list mirrors typed entries when the LLM omits cited_paper_ids.
        assertThat(r.citedPaperIds()).containsExactlyInAnyOrder("P-aaaa-1", "P-bbbb-2");
    }

    @Test
    void unknownTypeFallsBackToExtends() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_papers":[{"id":"P-aaaa-1","type":"BANANA","confidence":0.5}]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations()).hasSize(1);
        assertThat(r.typedCitations().get(0).type()).isEqualTo(RelationshipType.EXTENDS);
    }

    @Test
    void confidenceIsClampedToValidRange() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_papers":[
                    {"id":"P-aaaa-1","type":"SUPPORTS","confidence":1.5},
                    {"id":"P-bbbb-2","type":"SUPPORTS","confidence":-0.2}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations().get(0).confidence()).isEqualTo(1.0);
        assertThat(r.typedCitations().get(1).confidence()).isEqualTo(0.0);
    }

    @Test
    void rejectsMalformedPaperIds() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_papers":[
                    {"id":"not-a-paper-id","type":"SUPPORTS","confidence":0.7},
                    {"id":"P-aaaa-1","type":"SUPPORTS","confidence":0.7}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations()).hasSize(1);
        assertThat(r.typedCitations().get(0).paperId()).isEqualTo("P-aaaa-1");
    }

    @Test
    void typedAndFlatCoexistWithFlatPreserved() {
        // LLM emits both shapes — the parser respects both.
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_paper_ids":["P-cccc-3"],
                 "cited_papers":[{"id":"P-aaaa-1","type":"SUPPORTS","confidence":0.8}]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations()).hasSize(1);
        assertThat(r.typedCitations().get(0).paperId()).isEqualTo("P-aaaa-1");
        // Flat list contains what the LLM provided; the mirror only kicks in
        // when cited_paper_ids was empty.
        assertThat(r.citedPaperIds()).containsExactly("P-cccc-3");
    }

    @Test
    void emptyTypedCitationsForLegacyResponse() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_paper_ids":["P-aaaa-1"]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.typedCitations()).isEmpty();
        assertThat(r.citedPaperIds()).containsExactly("P-aaaa-1");
    }
}
