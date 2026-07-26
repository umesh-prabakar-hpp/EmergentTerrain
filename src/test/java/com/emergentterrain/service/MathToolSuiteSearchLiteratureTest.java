package com.emergentterrain.service;

import com.emergentterrain.research.LiteratureSearchService;
import com.emergentterrain.research.PaperDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lever 2 — the search_literature citation tool: advertised only when a corpus
 * is wired AND the caller opts in; returns curated references for the draft's
 * (domain, topic) and a clear no-references message on a miss. The result must
 * NOT carry a {@link MathToolSuite#SUCCESS_PREFIXES} marker (a lookup is not a
 * computational success).
 */
class MathToolSuiteSearchLiteratureTest {

    private static final ObjectMapper M = new ObjectMapper();
    private MathToolSuite suite;

    @BeforeEach
    void setUp() {
        suite = new MathToolSuite((com.emergentterrain.service.LlmRouter) null, M);
        LiteratureSearchService lit =
                new LiteratureSearchService(new ClassPathResource("literature-corpus.yml"));
        lit.loadCorpus();
        suite.setLiteratureSearch(lit);
    }

    private static PaperDraft draft(String domain, String topic) {
        return new PaperDraft(null, "bot", domain, topic, "title",
                null, null, null, null, List.of(),
                0.0, 0.0, 0.0, 0L, null, 0, "P-test",
                null, null, false, null, null,
                List.of(), null, null, null, null);
    }

    @Test
    void toolAdvertisedOnlyWhenIncludeLiteratureAndCorpusWired() {
        assertThat(suite.toolsJson(false, true)).contains("search_literature");
        assertThat(suite.toolsJson(false, false)).doesNotContain("search_literature");
        // No corpus wired ⇒ omitted even when opted in.
        MathToolSuite bare = new MathToolSuite((com.emergentterrain.service.LlmRouter) null, M);
        assertThat(bare.toolsJson(false, true)).doesNotContain("search_literature");
    }

    @Test
    void fullyEnabledToolsJsonParsesWhenLiteratureToolIsWired() throws Exception {
        String json = suite.toolsJson(true, true);

        assertThat(json).contains("search_literature");
        assertThat(M.readTree(json).isArray()).isTrue();
    }

    @Test
    void returnsCuratedReferenceForKnownTopic() {
        Function<String, String> exec =
                suite.buildExecutor(draft("MATHEMATICS", "Collatz Stopping Time"),
                        new MathToolSuite.ToolMetrics());
        String r = exec.apply("{\"name\":\"search_literature\",\"arguments\":{}}");
        assertThat(r).startsWith("search_literature:").contains("Lagarias");
        // Not a computational success.
        assertThat(MathToolSuite.isSuccessResult(r)).isFalse();
    }

    @Test
    void reportsNoReferencesOnMiss() {
        Function<String, String> exec =
                suite.buildExecutor(draft("MATHEMATICS", "No Such Topic At All"),
                        new MathToolSuite.ToolMetrics());
        String r = exec.apply("{\"name\":\"search_literature\",\"arguments\":{}}");
        assertThat(r).contains("no references found");
        assertThat(MathToolSuite.isSuccessResult(r)).isFalse();
    }
}
