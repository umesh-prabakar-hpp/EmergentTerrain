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

    @Test
    void mentoringGenomeRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        // Same inputs the pre-carve buildGenomeMentoringPrompt would have used.
        String botId8     = "abcdef12";
        String domain     = "MATHEMATICS";
        String method     = "THEORETICAL";
        int    gen        = 7;
        double w0 = 0.3, w1 = 0.2, w2 = 0.5, w3 = 0.5, w4 = 0.05, er = 0.2;
        int    pc1 = 50;
        double pc2 = 5.0;
        int    pr1 = 35;
        double pr2 = 3.5;
        int    pub = 12, rej = 3;
        String own = "  MATHEMATICS / abundance — score 0.81";
        String peer = "  bot-abc123: MATHEMATICS / collatz — score 0.66";
        String crossDomain = "\nAnomalous findings in OTHER domains (cross-domain inspiration from peers):\n"
                           + "  bot-fff999: PHYSICS / two-body — score 0.90 [z=+2.3]: striking trajectory\n";

        // Numeric pre-format inside the caller (matches the new code path).
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("botId8",                 botId8);
        params.put("domain",                 domain);
        params.put("methodology",            method);
        params.put("generation",             String.valueOf(gen));
        params.put("w0",                     String.format("%.2f", w0));
        params.put("w1",                     String.format("%.2f", w1));
        params.put("w2",                     String.format("%.2f", w2));
        params.put("w3",                     String.format("%.2f", w3));
        params.put("w4",                     String.format("%.2f", w4));
        params.put("explorationRate",        String.format("%.2f", er));
        params.put("paramCenter1",           String.valueOf(pc1));
        params.put("paramCenter2",           String.format("%.1f", pc2));
        params.put("paramRadius1",           String.valueOf(pr1));
        params.put("paramRadius2",           String.format("%.1f", pr2));
        params.put("publishedCount",         String.valueOf(pub));
        params.put("rejectedCount",          String.valueOf(rej));
        params.put("ownWork",                own);
        params.put("peerWork",               peer);
        params.put("crossDomainAnomalies",   crossDomain);

        String registryRendered = r.render(PromptTemplateIds.MENTORING_GENOME_ID, params);

        // Reproduce the pre-migration template body byte-for-byte. The original
        // used "%%" for literal "%" in two example-directive lines; the new body
        // uses literal "%", so the pre-format here must produce the same output.
        String inlineExpected = """
                You are a research advisor in a simulation. A researcher bot has just published \
                notable work and is eligible for a mentoring session.

                Researcher: %s  |  Domain: %s  |  Methodology: %s
                Generation: %d  (evolution and mentoring cycles completed)

                Research personality (genome):
                  Step size:             %.2f  — spacing between candidate regions (0.05=narrow, 1.0=wide)
                  Restart willingness:   %.2f  — reserved, not yet active
                  Candidate breadth:     %.2f  — regions explored per paper (20–80; key quality driver)
                  Peer tolerance:        %.2f  — reviewer strictness (low=strict 0.02, high=lenient 0.12)
                  Acceptance threshold:  %.2f  — reserved for future gating
                  Curiosity:             %.2f  — overall openness to new directions

                Parameter search region (learned from publication history):
                  Center: [param1=%d, param2=%.1f]  — where they focus searches
                  Radius: [±%d, ±%.1f]              — how broadly they explore around that center
                  (narrowing radius = deepening specialisation; wider = more exploratory)

                Career so far: %d papers published, %d drafts not accepted
                Their own recent work:
                %s
                Recent work by peers in the same domain:
                %s%s
                As their advisor, consider what the peer findings reveal about this domain. \
                Pay particular attention to results marked [ANOMALOUS] — these deviate significantly \
                from the room's historical distribution for that topic and may indicate unexplored \
                territory or a structural pattern worth investigating. \
                If cross-domain anomalies are shown above, consider whether any structural pattern \
                (e.g. a mathematical property, a scale threshold, a domain boundary) might transfer \
                to this researcher's domain — if so, incorporate that into the directive or search \
                center adjustment. Suggest one or two small genome adjustments.

                Reply with only this JSON and nothing else:
                {"identity":"one sentence on their emerging research identity","edits":[{"gene":"w0","delta":0.05}],"directive":{"description":"your description","rangeStart":N,"rangeEnd":M,"predicate":"your predicate","metric":"your metric","baseline":0.0,"hypothesis":"your hypothesis"}}
                Gene rules: gene must be one of w0 w1 w2 w3 w4 explorationRate paramCenter1 paramCenter2. \
                delta between -0.15 and 0.15. Include 1 or 2 edits. \
                For paramCenter1: delta ±0.15 shifts the center by ±6 units (range 10–99). \
                For paramCenter2: delta ±0.15 shifts by ±0.75 (range 1.0–9.0).
                Directive rules: predicate uses isPrime, isHappy, collatzTerminates, isAbundant, isPerfect joined with AND or OR. \
                metric is one of: fraction_satisfying, mean_of:collatzSteps, max_of:collatzSteps, mean_of:sigmaSum. \
                rangeEnd - rangeStart must not exceed 500. baseline is your predicted numeric value for the metric. \
                Base the directive on what the ACTUAL peer papers above reveal — target a specific unexplored range \
                or combination that their results point toward. \
                Only include directive if the peer papers reveal a specific unexplored range worth targeting. Omit directive entirely otherwise.
                Example directives (DO NOT copy these — generate your own based on the actual papers shown above):
                  {"description":"abundant numbers in range 200-400","rangeStart":200,"rangeEnd":400,"predicate":"isAbundant","metric":"fraction_satisfying","baseline":0.25,"hypothesis":"About 25%% of integers in 200-400 are abundant, consistent with the known asymptotic density."}
                  {"description":"mean Collatz depth for happy primes near 800","rangeStart":780,"rangeEnd":950,"predicate":"isPrime AND isHappy","metric":"mean_of:collatzSteps","baseline":75.0,"hypothesis":"Happy primes near 800 require about 75 Collatz steps to terminate, deeper than the general average."}
                  {"description":"perfect or abundant numbers below 300","rangeStart":2,"rangeEnd":302,"predicate":"isAbundant OR isPerfect","metric":"fraction_satisfying","baseline":0.24,"hypothesis":"Roughly 24%% of integers below 302 are abundant or perfect."}
                """.formatted(
                        botId8, domain, method, gen,
                        w0, w1, w2, w3, w4, er,
                        pc1, pc2, pr1, pr2,
                        pub, rej, own, peer, crossDomain);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    @Test
    void formulationMainRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain        = "MATHEMATICS";
        String topicName     = "collatz";
        int    center1       = 47;
        int    radius1       = 12;
        double center2       = 5.3;
        double radius2       = 1.7;
        String identitySection   = "Researcher style: pragmatic, focuses on edge cases.\n";
        String hypothesisSection = "Researcher's current research direction: depth of Collatz for primes\n";
        String noveltyContext    = "NOVELTY NOTE (score=0.45/1.0): Suggested unexplored angle: try larger seeds\n";
        String priorTrimmed      = "  P-1 [score 0.81]: collatz steps grow modestly";
        String anomalyContext    = "Anomalous findings in adjacent topics (same domain) — consider whether similar "
                                 + "parameter regions might be interesting in this topic too:\n"
                                 + "  P-2 / abundance [z=+2.0 score=0.70]: striking density\n\n";

        String optionalContext = identitySection + hypothesisSection + noveltyContext;
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",          domain);
        params.put("topicName",       topicName);
        params.put("center1",         String.valueOf(center1));
        params.put("radius1",         String.valueOf(radius1));
        params.put("center2",         String.format("%.1f", center2));
        params.put("radius2",         String.format("%.1f", radius2));
        params.put("optionalContext", optionalContext);
        params.put("priorPapers",     priorTrimmed);
        params.put("anomalyContext",  anomalyContext);

        String registryRendered = r.render(PromptTemplateIds.FORMULATION_MAIN_ID, params);

        // Reproduce the pre-migration template body byte-for-byte.
        String inlineExpected = """
                You are a research advisor choosing where a computational researcher should focus next.

                Domain: %s | Topic: %s
                Researcher's learned search region: param1 center=%d (±%d), param2 center=%.1f (±%.1f)
                param1 is an integer 10–99 (controls the starting position of the search space).
                param2 is a decimal 1.0–9.0 (controls depth/scale of the search).
                %s%s%s
                Prior papers in this topic:
                %s%s
                Based on the prior work, choose a specific unexplored or promising parameter region
                within or near the researcher's learned search region. Explain the research question
                this choice targets in one sentence.

                Before any computation runs, pre-register your hypothesis. This is locked and \
                cannot be revised once results are seen.

                Reply with ONLY this JSON, no other text:
                {"param1":N,"param2":X.X,"question":"one sentence research question",\
                "pre_registration":{"hypothesis":"one testable claim this parameter choice will investigate",\
                "prediction":"expected direction or magnitude (e.g. monotone increase, value near 0.3)",\
                "falsification_condition":"what outcome would contradict this (e.g. no correlation, 0 exceedance)",\
                "mapping_notes":"if crossing domains: state any constants or analogies and their justification; empty string otherwise"}}

                param1 must be an integer 10–99. param2 must be a decimal 1.0–9.0.
                Choose values that differ from or extend what prior papers have already explored.
                """.formatted(domain, topicName, center1, radius1, center2, radius2,
                        identitySection, hypothesisSection, noveltyContext,
                        priorTrimmed, anomalyContext);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    private static PromptTemplateRegistry freshSeededRegistry() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.seedDefaults();   // package-private, invoked here exactly like @PostConstruct would.
        return r;
    }
}
