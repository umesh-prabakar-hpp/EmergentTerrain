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

    @Test
    void parsesPartialV2RevisionWithOnlyDiscussionAndRelatedWork() {
        // The student-revision LLM under the v2 template sometimes emits only the
        // changed sections (discussion, related_work) plus a single core key, and
        // omits the others when they're unchanged from first-pass. The parser
        // returns those keys as nulls; the caller in maybeCritiqueAndReviseWithTools
        // is responsible for merging them with firstPass values rather than
        // discarding the whole revision. Without this tolerance, 2/2 of the
        // a3289005 May-2026 corpus prof:ok papers hit rev:parse_failed→fp.
        String json = """
                {"hypothesis":"unchanged hypothesis text — same as first-pass",
                 "confidenceScore":0.8,
                 "discussion":"The revised analysis suggests asymptotic stability holds locally.",
                 "related_work":"Hardy & Littlewood (1923) provides the canonical bound."}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.hypothesis()).contains("unchanged hypothesis");
        assertThat(r.findings()).isNull();   // absent → null, NOT a parse failure
        assertThat(r.conclusion()).isNull(); // absent → null, NOT a parse failure
        assertThat(r.discussion()).contains("asymptotic stability");
        assertThat(r.relatedWork()).contains("Hardy & Littlewood");
        assertThat(r.confidenceScore()).isEqualTo(0.8);
    }
}
