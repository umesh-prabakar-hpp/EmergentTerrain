package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4c Piece 2 — pins the registry infrastructure and proves the first
 * migrated template ({@link PromptTemplateIds#FORMULATION_NOVELTY_ID})
 * renders the same string the old inline {@code String.format(...)} would
 * have produced. The remaining 10 template migrations follow the same
 * pattern; each gets its own equivalence test as it lands.
 */
class PromptTemplateRegistryTest {

    // ---- renderer ----

    @Test
    void renderTemplateSubstitutesSimpleSlots() {
        String body = "Hello {{ who }}, today is {{ day }}.";
        String out = PromptTemplateRegistry.renderTemplate(body,
                Map.of("who", "world", "day", "Tuesday"));

        assertThat(out).isEqualTo("Hello world, today is Tuesday.");
    }

    @Test
    void renderTemplateToleratesWhitespaceInsideBraces() {
        // Both {{name}} (no padding) and {{ name }} (padded) resolve.
        String out = PromptTemplateRegistry.renderTemplate(
                "{{a}}+{{ b }}+{{  c  }}",
                Map.of("a", "1", "b", "2", "c", "3"));
        assertThat(out).isEqualTo("1+2+3");
    }

    @Test
    void renderTemplateThrowsOnMissingSlotValue() {
        // Missing slots are programmer errors, not silent empties — catches typos.
        assertThatThrownBy(() -> PromptTemplateRegistry.renderTemplate(
                        "Hello {{ name }}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void renderTemplateLeavesUnknownPunctuationAlone() {
        // Single braces, malformed patterns, etc. pass through unchanged.
        String out = PromptTemplateRegistry.renderTemplate(
                "{not-a-slot} and { spaced } and {{ ok }}",
                Map.of("ok", "X"));
        assertThat(out).isEqualTo("{not-a-slot} and { spaced } and X");
    }

    @Test
    void renderTemplateDoesNotRecurseIntoReplacementValues() {
        // A replacement containing another {{ slot }} is left literal — keeps the
        // contract trivial and prevents infinite-loop foot-guns.
        String out = PromptTemplateRegistry.renderTemplate(
                "{{ outer }}",
                Map.of("outer", "{{ inner }}"));
        assertThat(out).isEqualTo("{{ inner }}");
    }

    @Test
    void renderTemplateTreatsNullValueAsEmptyString() {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("name", null);
        assertThat(PromptTemplateRegistry.renderTemplate("Hello {{ name }}!", params))
                .isEqualTo("Hello !");
    }

    // ---- registry ----

    @Test
    void registerAppendsAndPickActiveReturnsLatestVersion() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.register(new PromptTemplate("x", "v1", "first"));
        r.register(new PromptTemplate("x", "v2", "second"));

        assertThat(r.pickActive("x")).isPresent();
        assertThat(r.pickActive("x").get().version()).isEqualTo("v2");
        assertThat(r.pickActive("x").get().body()).isEqualTo("second");
    }

    @Test
    void registerSameVersionReplacesInPlace() {
        // Registering v1 twice should not produce two list entries.
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.register(new PromptTemplate("x", "v1", "first"));
        r.register(new PromptTemplate("x", "v1", "first-rewritten"));

        assertThat(r.variants("x")).hasSize(1);
        assertThat(r.pickActive("x").get().body()).isEqualTo("first-rewritten");
    }

    @Test
    void pickActiveReturnsEmptyForUnknownId() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        assertThat(r.pickActive("nope")).isEmpty();
    }

    @Test
    void renderByIdThrowsForUnknownId() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        assertThatThrownBy(() -> r.render("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void renderByIdAndVersionPicksPinnedVariant() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.register(new PromptTemplate("x", "v1", "version-{{ which }}"));
        r.register(new PromptTemplate("x", "v2", "v2-body-{{ which }}"));

        assertThat(r.render("x", "v1", Map.of("which", "one")))
                .isEqualTo("version-one");
        assertThat(r.render("x", "v2", Map.of("which", "two")))
                .isEqualTo("v2-body-two");
    }

    @Test
    void renderByIdAndVersionThrowsForUnknownVersion() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.register(new PromptTemplate("x", "v1", "body"));
        assertThatThrownBy(() -> r.render("x", "v99", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v99");
    }

    @Test
    void registerRejectsNullTemplate() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        assertThatThrownBy(() -> r.register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- seedDefaults + first migrated template ----

    @Test
    void seedDefaultsRegistersTheMigratedTemplates() {
        // Each PromptTemplateIds constant the registry has migrated should be
        // resolvable. Today: only FORMULATION_NOVELTY. Future migrations extend
        // this list as they land.
        PromptTemplateRegistry r = freshSeededRegistry();

        assertThat(r.pickActive(PromptTemplateIds.FORMULATION_NOVELTY_ID))
                .as("FORMULATION_NOVELTY")
                .isPresent();
        assertThat(r.pickActive(PromptTemplateIds.FORMULATION_NOVELTY_ID).get().version())
                .isEqualTo(PromptTemplateIds.FORMULATION_NOVELTY_VERSION);
    }

    @Test
    void formulationNoveltyRendersToSameStringAsOldInlineFormat() {
        // Equivalence test: the registry-rendered v1 must produce exactly the
        // same string the pre-migration String.format / "...".formatted(...)
        // would have produced for the same inputs. This is the regression
        // protection the memo asked for ("rendered output, not template body").
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain     = "PHYSICS";
        String topic      = "two-body";
        long totalCount   = 7L;
        String recent     = "  P-1 [score 0.85]: results match prior runs\n"
                          + "  P-2 [score 0.40]: noisy outcome";

        String registryRendered = r.render(PromptTemplateIds.FORMULATION_NOVELTY_ID, Map.of(
                "domain",       domain,
                "topicName",    topic,
                "totalCount",   String.valueOf(totalCount),
                "recentPapers", recent));

        // Reproduce the pre-migration template body byte-for-byte, using the
        // old positional .formatted(...) call shape.
        String inlineExpected = """
                You are assessing how much computational novelty remains in a research topic.

                Domain: %s | Topic: %s
                Total papers published in this topic so far: %d

                Most recent papers (findings summaries):
                %s
                Rate how much unexplored territory remains: 1.0 = completely fresh, 0.0 = fully saturated.
                Note which parameter regions or angles have NOT been tried yet.

                Reply with ONLY this JSON — no other text:
                {"novelty_score":0.7,"coverage_note":"brief note on what has been covered","unexplored_hint":"specific suggestion for an unexplored region or angle; empty string if no clear gap"}
                """.formatted(domain, topic, totalCount, recent);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    private static PromptTemplateRegistry freshSeededRegistry() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.seedDefaults();   // package-private, invoked here exactly like @PostConstruct would.
        return r;
    }
}
