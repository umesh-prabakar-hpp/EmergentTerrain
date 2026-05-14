package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 F7.1 — parser + clamp tests for the multi-reviewer panel response
 * record. Each test pins one behaviour of {@link PanelReview#parse(String, String)}
 * so F7.2's orchestrator can rely on the contract when fanning out to N providers.
 */
class PanelReviewTest {

    @Test
    void parsesCompleteWellFormedJson() {
        String json = """
                {
                  "grades": {"honesty":0.85, "completeness":0.70, "rigor":0.90, "novelty":0.60},
                  "cited_calls": [1, 3, 5],
                  "omitted_relevant": [2],
                  "contradictions": [{"trace_idx":4, "vs_prose":"sweep shows opposite trend"}],
                  "recommendations": ["add boundary check at L=5mH"]
                }
                """;
        Optional<PanelReview> parsed = PanelReview.parse(json, "groq/llama-3.3-70b");
        assertThat(parsed).isPresent();
        PanelReview r = parsed.get();
        assertThat(r.reviewerId()).isEqualTo("groq/llama-3.3-70b");
        assertThat(r.grades().honesty()).isEqualTo(0.85);
        assertThat(r.grades().completeness()).isEqualTo(0.70);
        assertThat(r.grades().rigor()).isEqualTo(0.90);
        assertThat(r.grades().novelty()).isEqualTo(0.60);
        assertThat(r.citedCalls()).containsExactly(1, 3, 5);
        assertThat(r.omittedRelevant()).containsExactly(2);
        assertThat(r.contradictions()).hasSize(1);
        assertThat(r.contradictions().get(0).traceIdx()).isEqualTo(4);
        assertThat(r.contradictions().get(0).vsProse()).isEqualTo("sweep shows opposite trend");
        assertThat(r.recommendations()).containsExactly("add boundary check at L=5mH");
    }

    @Test
    void parsesMinimalJsonWithEmptyCollectionsDefault() {
        // A reviewer who finds nothing wrong should still parse fine.
        String json = "{\"grades\":{\"honesty\":0.9,\"completeness\":0.8,\"rigor\":0.85,\"novelty\":0.5}}";
        Optional<PanelReview> parsed = PanelReview.parse(json, "nvidia/nemotron");
        assertThat(parsed).isPresent();
        PanelReview r = parsed.get();
        assertThat(r.citedCalls()).isEmpty();
        assertThat(r.omittedRelevant()).isEmpty();
        assertThat(r.contradictions()).isEmpty();
        assertThat(r.recommendations()).isEmpty();
    }

    @Test
    void clampsOutOfRangeGrades() {
        // A misbehaving reviewer cannot poison aggregation: 1.5 → 1.0, -0.3 → 0.0, NaN → 0.0.
        String json = "{\"grades\":{\"honesty\":1.5,\"completeness\":-0.3,\"rigor\":0.8,\"novelty\":\"NaN\"}}";
        Optional<PanelReview> parsed = PanelReview.parse(json, "cerebras/qwen");
        assertThat(parsed).isPresent();
        PanelReview.Grades g = parsed.get().grades();
        assertThat(g.honesty()).isEqualTo(1.0);
        assertThat(g.completeness()).isEqualTo(0.0);
        assertThat(g.rigor()).isEqualTo(0.8);
        assertThat(g.novelty()).isEqualTo(0.0);  // NaN → 0
    }

    @Test
    void missingGradesObjectFailsParse() {
        // Without a grades block there's nothing aggregatable — drop the reviewer.
        String json = "{\"cited_calls\":[1,2]}";
        assertThat(PanelReview.parse(json, "openrouter/gpt-oss-120b")).isEmpty();
    }

    @Test
    void malformedJsonFailsParse() {
        assertThat(PanelReview.parse("not json", "groq/x")).isEmpty();
        assertThat(PanelReview.parse("[]", "groq/x")).isEmpty();         // array, not object
        assertThat(PanelReview.parse("\"string\"", "groq/x")).isEmpty(); // bare string
        assertThat(PanelReview.parse("", "groq/x")).isEmpty();
        assertThat(PanelReview.parse(null, "groq/x")).isEmpty();
    }

    @Test
    void contradictionsMissingTraceIdxOrProseAreDropped() {
        String json = """
                {"grades":{"honesty":0.5,"completeness":0.5,"rigor":0.5,"novelty":0.5},
                 "contradictions":[
                   {"trace_idx":2,"vs_prose":"ok"},
                   {"trace_idx":-1,"vs_prose":"bad idx"},
                   {"vs_prose":"missing idx"},
                   {"trace_idx":3},
                   {"trace_idx":4,"vs_prose":""}
                 ]}
                """;
        PanelReview r = PanelReview.parse(json, "groq/x").orElseThrow();
        assertThat(r.contradictions()).hasSize(1);
        assertThat(r.contradictions().get(0).traceIdx()).isEqualTo(2);
    }

    @Test
    void parsesStringEncodedIntsInCitedCalls() {
        // Some reviewers emit "1" instead of 1; tolerate it.
        String json = """
                {"grades":{"honesty":0.5,"completeness":0.5,"rigor":0.5,"novelty":0.5},
                 "cited_calls":["1","2","not-a-number",3]}
                """;
        PanelReview r = PanelReview.parse(json, "groq/x").orElseThrow();
        assertThat(r.citedCalls()).containsExactly(1, 2, 3);
    }

    @Test
    void unknownTopLevelKeysIgnored() {
        String json = """
                {"grades":{"honesty":0.5,"completeness":0.5,"rigor":0.5,"novelty":0.5},
                 "thoughts":"some chain of thought leaked",
                 "extra_metric":42}
                """;
        assertThat(PanelReview.parse(json, "groq/x")).isPresent();
    }

    @Test
    void emptyRecommendationStringsAreDropped() {
        String json = """
                {"grades":{"honesty":0.5,"completeness":0.5,"rigor":0.5,"novelty":0.5},
                 "recommendations":["", "  ", "real recommendation"]}
                """;
        PanelReview r = PanelReview.parse(json, "groq/x").orElseThrow();
        assertThat(r.recommendations()).containsExactly("real recommendation");
    }
}
