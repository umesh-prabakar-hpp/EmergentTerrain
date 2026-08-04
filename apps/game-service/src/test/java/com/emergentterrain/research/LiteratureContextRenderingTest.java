package com.emergentterrain.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 D1.3 — pins the v2-with-literature template renders against synthetic
 * literature context strings and the LiteratureSearchService lookups.
 *
 * <p>The narrator-side wiring inside ResearchNarrationService is harder to
 * exercise without LlmRouter mocking; these tests cover the
 * template-and-corpus boundary the wiring crosses.
 */
class LiteratureContextRenderingTest {

    private PromptTemplateRegistry registry;
    private LiteratureSearchService literature;

    @BeforeEach
    void setUp() {
        registry = new PromptTemplateRegistry();
        registry.seedDefaults();
        literature = new LiteratureSearchService(new ClassPathResource("literature-corpus.yml"));
        literature.loadCorpus();
    }

    // ── v2 template variants render with the literatureContext slot ──

    @Test
    void v2WithLiteratureRendersSystemPromptCarryingTheBlock() {
        String literatureContext = "\nPRIOR LITERATURE FOR THIS REGION:\n"
                + "- Hardy & Littlewood (1923): pi2(N) ~ 2C2*N/ln^2(N)\n";
        String rendered = registry.render(
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                "v2-with-literature",
                Map.of("literatureContext", literatureContext, "auditSection", ""));
        assertThat(rendered)
                .contains("PRIOR LITERATURE FOR THIS REGION:")
                .contains("Hardy & Littlewood (1923)")
                .contains("note whether the paper's claims align");
    }

    @Test
    void v2WithLiteratureRendersUserPromptCarryingTheBlock() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain", "MATHEMATICS");
        params.put("topicName", "Prime Constellation Search");
        params.put("preRegAndClassLines", "");
        params.put("literatureContext", "\nPRIOR LITERATURE:\n- Hardy & Littlewood (1923).\n");
        params.put("hypothesis", "h");
        params.put("findings", "f");
        params.put("conclusion", "c");
        String rendered = registry.render(
                PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                "v2-with-literature", params);
        assertThat(rendered)
                .contains("PRIOR LITERATURE:")
                .contains("Hardy & Littlewood (1923)")
                .contains("Domain: MATHEMATICS | Topic: Prime Constellation Search");
    }

    @Test
    void v2FromSynthesisWithLiteratureRendersRevisionCarryingTheBlock() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramsLine", "");
        params.put("domain", "PHYSICS");
        params.put("topicName", "RLC Damping Transition");
        params.put("professorSynthesis", "professor said …");
        params.put("hypothesis", "h");
        params.put("findings", "f");
        params.put("conclusion", "c");
        params.put("conclusionSpec", "\"conclusion\":\"…\"");
        params.put("literatureContext", "\nPRIOR LITERATURE:\n- Maxwell 1873.\n");
        String rendered = registry.render(
                PromptTemplateIds.NARRATION_REVISION_ID,
                "v2-from-synthesis-with-literature", params);
        assertThat(rendered)
                .contains("PRIOR LITERATURE:")
                .contains("Maxwell 1873")
                .contains("PROFESSOR'S REVIEW:");
    }

    // ── v1 variants are still selectable by explicit version ──

    @Test
    void v1ProfessorSystemStillRendersWithoutLiteratureSlot() {
        String rendered = registry.render(
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION,
                Map.of("auditSection", ""));
        assertThat(rendered)
                .doesNotContain("PRIOR LITERATURE")
                .contains("You are a professor reviewing a student");
    }

    // ── Literature lookup feeds the rendered block end-to-end ──

    @Test
    void literatureLookupForPrimeConstellationCanBeStitchedIntoTheV2Slot() {
        // Compose what ResearchNarrationService.renderLiteratureContext produces
        // and assert the v2 template renders without complaint.
        var refs = literature.findFor("MATHEMATICS", "Prime Constellation Search");
        assertThat(refs).isNotEmpty();
        StringBuilder sb = new StringBuilder("\nPRIOR LITERATURE FOR THIS REGION:\n");
        for (LiteratureReference r : refs) {
            sb.append("- ").append(r.citation()).append(": ").append(r.closedForm()).append('\n');
        }
        String rendered = registry.render(
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                "v2-with-literature",
                Map.of("literatureContext", sb.toString(), "auditSection", ""));
        assertThat(rendered)
                .contains("Hardy")
                .contains("PRIOR LITERATURE FOR THIS REGION:");
    }
}
