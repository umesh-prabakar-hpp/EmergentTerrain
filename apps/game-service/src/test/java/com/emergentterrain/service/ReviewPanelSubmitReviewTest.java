package com.emergentterrain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.emergentterrain.research.PanelReview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 29 §2.B — schema-pinned reviewer. Pins the {@code submit_review} tool
 * schema (validity, single tool, grok-safe with no '/'-bearing enum) and that
 * the args a tool-capable model would pass round-trip cleanly through
 * {@link PanelReview#parse} — the property that makes the tool path
 * parse-failure-proof.
 */
class ReviewPanelSubmitReviewTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void submitReviewToolsJsonIsValidSingleTool() throws Exception {
        JsonNode arr = M.readTree(ReviewPanelService.SUBMIT_REVIEW_TOOLS_JSON);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).path("function").path("name").asText())
                .isEqualTo(ReviewPanelService.SUBMIT_REVIEW_TOOL_NAME);
        // the four grade axes must be present so PanelReview.parse finds them
        JsonNode gradeProps = arr.get(0).path("function").path("parameters")
                .path("properties").path("grades").path("properties");
        assertThat(gradeProps.has("honesty")).isTrue();
        assertThat(gradeProps.has("completeness")).isTrue();
        assertThat(gradeProps.has("rigor")).isTrue();
        assertThat(gradeProps.has("novelty")).isTrue();
    }

    @Test
    void submitReviewSchemaHasNoSlashBearingEnum() throws Exception {
        // §1.A lesson, applied to the new tool: xAI/grok rejects tool schemas
        // whose enum string values contain '/'. This schema must use none.
        assertNoSlashEnum(M.readTree(ReviewPanelService.SUBMIT_REVIEW_TOOLS_JSON));
    }

    private static void assertNoSlashEnum(JsonNode node) {
        if (node.isObject()) {
            JsonNode en = node.get("enum");
            if (en != null && en.isArray()) {
                for (JsonNode v : en) {
                    assertThat(v.asText()).doesNotContain("/");
                }
            }
            node.fields().forEachRemaining(e -> assertNoSlashEnum(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(ReviewPanelSubmitReviewTest::assertNoSlashEnum);
        }
    }

    @Test
    void submitReviewArgsRoundTripThroughPanelReviewParse() {
        // The exact shape a tool-capable model passes as submit_review arguments
        // — i.e. what callReviewerViaTool captures and hands to PanelReview.parse.
        String args = """
            {"grades":{"honesty":0.8,"completeness":0.6,"rigor":0.7,"novelty":0.4},
             "cited_calls":[1,3],"omitted_relevant":[2],
             "contradictions":[{"trace_idx":2,"vs_prose":"prose says 5, call_2 returned 7"}],
             "recommendations":["state the boundary condition"],
             "evidence_label":"EMPIRICAL_RESULT"}""";
        Optional<PanelReview> parsed = PanelReview.parse(args, "openrouter/deepseek/deepseek-v4-pro");
        assertThat(parsed).isPresent();
        PanelReview r = parsed.get();
        assertThat(r.grades().honesty()).isEqualTo(0.8);
        assertThat(r.grades().rigor()).isEqualTo(0.7);
        assertThat(r.citedCalls()).containsExactly(1, 3);
        assertThat(r.omittedRelevant()).containsExactly(2);
        assertThat(r.contradictions()).hasSize(1);
        assertThat(r.contradictions().get(0).traceIdx()).isEqualTo(2);
        assertThat(r.proposedLabel()).isPresent();
    }
}
