package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 D2 — parser tests for the optional {@code discussion} and
 * {@code related_work} JSON keys. Both nullable; the parser tolerates
 * absent / blank / non-string values.
 */
class NarrationDiscussionParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesDiscussionAndRelatedWorkWhenPresent() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c",
                 "confidenceScore":0.7,
                 "discussion":"The result raises questions about asymptotic stability.",
                 "related_work":"Hardy & Littlewood (1923) is the canonical reference."}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.discussion()).contains("asymptotic stability");
        assertThat(r.relatedWork()).contains("Hardy & Littlewood");
    }

    @Test
    void returnsNullDiscussionAndRelatedWorkWhenAbsent() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.discussion()).isNull();
        assertThat(r.relatedWork()).isNull();
    }

    @Test
    void returnsNullDiscussionWhenBlank() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5,
                 "discussion":"",
                 "related_work":"   "}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.discussion()).isNull();
        assertThat(r.relatedWork()).isNull();
    }

    @Test
    void returnsNullWhenDiscussionIsNonString() {
        // A defensive parser handles the LLM-emits-object-instead-of-string case.
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5,
                 "discussion":{"nested":"object"}}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.discussion()).isNull();
    }
}
