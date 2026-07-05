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

    @Test
    void narrationAuditRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain         = "PHYSICS";
        String topicName      = "two-body";
        String classification = "POSITIVE_RESULT";
        String hypothesis     = "energy is conserved";
        String findings       = "deviation < 0.5% across all runs";
        String conclusion     = "supports the conservation hypothesis";

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         domain);
        params.put("topicName",      topicName);
        params.put("classification", classification);
        params.put("hypothesis",     hypothesis);
        params.put("findings",       findings);
        params.put("conclusion",     conclusion);

        // Pin to v1 explicitly: G7 registered a v2-with-boundary-answer variant
        // that is now active. v1 stays in the registry for provenance and is what
        // this regression test against the original inline body is asserting.
        String registryRendered = r.render(PromptTemplateIds.NARRATION_AUDIT_ID,
                PromptTemplateIds.NARRATION_AUDIT_VERSION, params);

        String inlineExpected = """
                Assess this computational research paper for quality gaps.
                Only flag material issues. Return an empty "gaps" array if the paper is fundamentally sound.

                Domain: %s | Topic: %s | Classification: %s
                Hypothesis: %s
                Findings: %s
                Conclusion: %s

                Check only these dimensions (skip if not problematic):
                - physical_mechanism: cross-domain mapping is purely definitional with no causal justification
                - degenerate_result: reported best result is a trivial edge case (e.g. zero/null/empty)
                - duplicate_computation: two or more computations tested identical parameter values
                - uncertainty: quantitative claims lack uncertainty language or error bounds
                - overclaiming: conclusion asserts more than the data demonstrates

                Reply with ONLY valid JSON:
                {"gaps":[{"dimension":"...","severity":"critical|moderate","description":"one sentence"}]}
                """.formatted(domain, topicName, classification, hypothesis, findings, conclusion);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    @Test
    void narrationAuditActiveVariantIsV3WithNonsequiturExemplars() {
        // Phase 10 — pickActive must now return v3-with-nonsequitur-exemplars.
        // The v2 body remains in the registry for the test below and for
        // historical provenance. v3 adds concrete known-bad cross-domain
        // mappings (Collatz→RLC, prime gaps→CTE) as exemplars to the
        // physical_mechanism dimension, plus a new threshold_drift check.
        PromptTemplateRegistry r = freshSeededRegistry();
        PromptTemplate active = r.pickActive(PromptTemplateIds.NARRATION_AUDIT_ID).orElseThrow();
        assertThat(active.version()).isEqualTo("v3-with-nonsequitur-exemplars");

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         "INTERDISCIPLINARY");
        params.put("topicName",      "Collatz-RLC Depth Resonance");
        params.put("classification", "SUPPORTED");
        params.put("hypothesis",     "h");
        params.put("findings",       "f");
        params.put("conclusion",     "c");
        // Phase 14 F6 — v3 now references {{ discussion }} too.
        params.put("discussion",     "");
        String rendered = r.render(PromptTemplateIds.NARRATION_AUDIT_ID, params);

        // Non-sequitur exemplars must be visible to the audit LLM:
        assertThat(rendered).contains("Collatz");
        assertThat(rendered).contains("prime");
        assertThat(rendered).contains("RLC");
        assertThat(rendered).contains("thermal expansion");
        assertThat(rendered).contains("no physical channel");

        // New threshold_drift dimension catches the Hypothesis-vs-Conclusion
        // numeric shift seen on P-347 (1%→5%), P-355 (5%→0.27%), P-357 (196→102 kJ/mol).
        assertThat(rendered).contains("threshold_drift");
        assertThat(rendered).contains("2×");

        // Pre-existing v2 dimensions still present in v3:
        assertThat(rendered).contains("boundary_answer");
        assertThat(rendered).contains("Vf=1.00");
        assertThat(rendered).contains("physical_mechanism");
        assertThat(rendered).contains("duplicate_computation");

        // Phase 14 F6 — Discussion slot is rendered + cross-domain-drift
        // instruction is present in the template body.
        assertThat(rendered).contains("Discussion (first ~150 words):");
        assertThat(rendered).contains("Phase 14 F6");
        assertThat(rendered).contains("INTERDISCIPLINARY");
        assertThat(rendered).contains("Van der Waals");
    }

    @Test
    void narrationAuditV3RendersDiscussionContentWhenProvided() {
        // Phase 14 F6 — when the caller supplies a Discussion slice, it
        // renders into the prompt so the audit-4b reviewer can scan for
        // cross-domain drift outside of Hypothesis / Findings / Conclusion.
        PromptTemplateRegistry r = freshSeededRegistry();
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         "MATHEMATICS");
        params.put("topicName",      "Collatz Stopping Time");
        params.put("classification", "SUPPORTED");
        params.put("hypothesis",     "h");
        params.put("findings",       "f");
        params.put("conclusion",     "c");
        params.put("discussion",
                "The mean stopping time aligns with Van der Waals critical exponents");
        String rendered = r.render(PromptTemplateIds.NARRATION_AUDIT_ID, params);

        assertThat(rendered).contains(
                "Discussion (first ~150 words): The mean stopping time aligns with Van der Waals");
    }

    @Test
    void narrationAuditV2RemainsAccessibleByExplicitVersion() {
        // v2 stays in the registry for historical regression; tests that pin
        // to it explicitly continue to render the v2 body.
        PromptTemplateRegistry r = freshSeededRegistry();
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         "MATERIALS");
        params.put("topicName",      "Composite Failure Mode");
        params.put("classification", "SUPPORTED");
        params.put("hypothesis",     "h");
        params.put("findings",       "f");
        params.put("conclusion",     "c");
        String v2 = r.render(PromptTemplateIds.NARRATION_AUDIT_ID,
                "v2-with-boundary-answer", params);
        assertThat(v2).contains("boundary_answer").contains("Vf=1.00");
        // v2 should NOT have the v3-only additions.
        assertThat(v2).doesNotContain("Collatz");
        assertThat(v2).doesNotContain("threshold_drift");
    }

    @Test
    void professorSystemRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        // Pre-carve buildProfessorSystemPrompt has two branches: empty audit
        // → empty section; non-empty audit → a "\nA quality audit identified
        // ..." block. The migrated body uses {{ auditSection }} for both.
        String auditSection = "\nA quality audit identified these specific gaps. Address them with your tools:\n"
                            + "- [CRITICAL] overclaiming: claims exceed evidence\n\n";

        // Phase 24.4 added a {{ srHint }} slot for the symbolic-regression
        // hint. The hint is empty when the SR engine isn't in use; pass
        // "" so the slot resolves cleanly and the pin matches both the
        // base template body and the Phase 471c1a6 "tool-grammar
        // reminders" block that came in after the original test was
        // written.
        String srHint = "";

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("auditSection", auditSection);
        params.put("srHint", srHint);
        String registryRendered = r.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION,
                params);

        // Expected body tracks the current template: original four-line
        // intro, auditSection + srHint slots, the Phase 471c1a6
        // tool-grammar reminders block (added without bumping the
        // template version because the additions are read-only
        // guidance), then the original "after any tool calls" coda.
        // The template puts {{ auditSection }}{{ srHint }} on its own
        // line, so the trailing newline from that line ends up between
        // the auditSection content (which itself ends in \n\n) and the
        // following "Tool-grammar reminders" block. Net: three newlines
        // = two blank lines between "[CRITICAL] overclaiming…" and the
        // tool-grammar header. The %s%s placeholders here also live on
        // their own line so the formatted string ends up with the
        // matching newline triple.
        String inlineExpected = """
                You are a professor reviewing a student's computational research paper.
                You have access to mathematical tools to investigate gaps you identify.
                Use tools when they would provide new evidence that materially changes what can be claimed.
                You do not need to use tools if the paper is already well-supported.
                %s%s
                Tool-grammar reminders (the safety reviewer rejects these common patterns):
                  - run_sandboxed_code is NOT full JavaScript. Arrays are fixed-size:
                    declare `var xs = [0,0,0,0,0];` and assign `xs[i] = v;` — there is
                    no `.push()`, no `.length`, no method calls. Use `len(xs)`.
                  - run_symbolic (Julia back-end) uses `^` for power, not `**` or `pow()`.
                    Write `x^2`, not `x**2` or `pow(x,2)`. Plain expressions only —
                    no top-level `let … end` blocks; if you need scoped names use
                    `(let x = …, y = …; expr end)` wrapped in parens on a single line.
                  - run_symbolic is Julia, NOT Mathematica. Use lowercase `pi` (or `π`),
                    never `PI`. There is no `PLANCK_H`/`HBAR`/`PLANCK` global — substitute
                    the numeric value (h = 6.62607015e-34, ħ = 1.054571817e-34) or bind it
                    inline. There is no `Solve[...]`/`NSolve[...]` — rearrange to a closed
                    form or evaluate numerically instead. (These undefined globals are the
                    top recurring run_symbolic eval errors.)
                  - If a call is REJECTED by safety review, READ the rejection reason —
                    it names the disallowed construct. Don't resubmit the same shape.
                After any tool calls, write a concise synthesis:
                  1. List specific textual weaknesses (overclaiming, missing error bounds,
                     inconsistency with pre-registration, unsupported leaps). Omit if none.
                  2. Summarise what your experiments revealed and how they bear on the claims.
                  3. BINDING REGIME: if a one-sided bound you tested HOLDS, do not stop there —
                     a held bound is only a loose envelope until you locate where it BINDS.
                     Spend one further call tightening the constant (use the tool's own
                     "tightest" suggestion when it gives one) until a slightly tighter version
                     is REFUTED, then report the pair — tightest hold + first refute — as the
                     demonstrated binding regime. A bound never shown to bind is not a result.
                Be direct and specific. If the paper is fundamentally sound, say so briefly.
                If run_sandboxed_code is REJECTED, revise the code to address the rejection reason
                and submit again — you have additional tool rounds available.
                """.formatted(auditSection, srHint);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    @Test
    void professorUserRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain        = "MATHEMATICS";
        String topicName     = "collatz";
        // preRegLine + classLine concatenated by the caller into one composite slot.
        String preRegLine    = "Pre-registered prediction: depth grows logarithmically\n";
        String classLine     = "Result classification: POSITIVE_RESULT\n";
        String composite     = preRegLine + classLine;
        String hypothesis    = "log(n) growth";
        String findings      = "fit r=0.91 on n<10k";
        String conclusion    = "weak evidence for log law";

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",              domain);
        params.put("topicName",           topicName);
        params.put("preRegAndClassLines", composite);
        params.put("hypothesis",          hypothesis);
        params.put("findings",            findings);
        params.put("conclusion",          conclusion);

        // Phase 6 D1.3: pin v1 explicitly; v2-with-literature renders a different body.
        String registryRendered = r.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_USER_VERSION, params);

        String inlineExpected = """
                Paper for review:
                Domain: %s | Topic: %s
                %s%s
                Hypothesis: %s
                Findings: %s
                Conclusion: %s
                """.formatted(domain, topicName, preRegLine, classLine, hypothesis, findings, conclusion);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    @Test
    void statisticalFramingRendersToSameStringAsOldInlineFormat() {
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain        = "MATERIALS";
        String topicName     = "composite-failure";
        String rawFindings   = "Matrix yield σ_ym=200.0000 MPa, fibre strength σ_f=3000.000 MPa.";

        String registryRendered = r.render(PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_ID, Map.of(
                "domain",      domain,
                "topicName",   topicName,
                "rawFindings", rawFindings));

        // Pre-carve template used "within ~X%%" for a literal "within ~X%"; the
        // migrated body uses a single literal "%" and produces the same output.
        String inlineExpected = """
                You are a scientific editor improving precision reporting in a computational paper.

                Domain: %s | Topic: %s

                Raw computational output:
                %s

                Rewrite this for the findings section of a scientific paper. Rules:
                1. Replace exact decimals beyond 3 significant figures with rounded values.
                2. Add appropriate uncertainty qualifiers: "approximately", "roughly", "within ~X%%", "±" estimates.
                3. For very small (<0.001) or very large (>10000) values use scientific notation.
                4. Do NOT change any facts, trends, relationships, or physical quantities — only the precision framing.
                5. Keep the same length and structure. Plain prose only, no bullet points, no headers.

                Reply with ONLY the rewritten findings text — no preamble, no JSON, no explanation.
                """.formatted(domain, topicName, rawFindings);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    @Test
    void narrationPrimaryRendersToSameStringAsOldInlineFormat() {
        // Byte-for-byte regression test for the largest template in the
        // registry. The five optional composite slots ({{ paramsLine }},
        // {{ followUpNote }}, {{ anomalyNote }}, {{ preRegSection }},
        // {{ conclusionSpec }}) follow the pre-carve %s%s%s + %s%s + %s
        // patterns. All other slots are direct value substitutions.
        PromptTemplateRegistry r = freshSeededRegistry();

        String domain        = "MATHEMATICS";
        String topicName     = "collatz";
        String methodology   = "EMPIRICAL";
        String title         = "Bounded-depth Collatz traces at scale";
        int    generation    = 5;
        String paramsLine    = "Computation parameters: starting position 47, scale 5.3\n";
        String followUpNote  = "IMPORTANT: This paper is a deliberate follow-up investigation — "
                             + "the author's prior conclusion suggested this exact parameter region.\n";
        String anomalyNote   = "NOTE: This result sits at z=+1.7 σ from the room baseline — "
                             + "moderately noteworthy.\n";
        String rawFindings   = "mean_steps=88.3 ± 4.1 across the sampled range";
        String scaffoldHyp   = "Collatz depth grows logarithmically with seed";
        String scaffoldCon   = "Consistent with the conjectured log-bound at this scale";
        String priorPapers   = "  P-1 [score 0.81]: collatz steps grow modestly";
        String preRegSection = "\nPre-registered hypothesis (locked before computation ran): log(n)\n"
                             + "Pre-registered prediction: monotonic\n"
                             + "Falsification condition: linear blowup\n";
        String conclusionSpec = "\"conclusion\":\"1-2 sentences: assess whether the pre-registered "
                              + "prediction was met (if applicable); what this result implies; one "
                              + "concrete open question it raises\"";

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         domain);
        params.put("topicName",      topicName);
        params.put("methodology",    methodology);
        params.put("title",          title);
        params.put("generation",     String.valueOf(generation));
        params.put("paramsLine",     paramsLine);
        params.put("followUpNote",   followUpNote);
        params.put("anomalyNote",    anomalyNote);
        params.put("rawFindings",    rawFindings);
        params.put("scaffoldHyp",    scaffoldHyp);
        params.put("scaffoldCon",    scaffoldCon);
        params.put("priorPapers",    priorPapers);
        params.put("preRegSection",  preRegSection);
        params.put("conclusionSpec", conclusionSpec);

        // Phase 6 D2 added a v2-with-discussion variant; this regression test
        // continues to pin the v1 body byte-for-byte.
        String registryRendered = r.render(PromptTemplateIds.NARRATION_PRIMARY_ID,
                PromptTemplateIds.NARRATION_PRIMARY_VERSION, params);

        // Reproduce the pre-migration template body byte-for-byte.
        String inlineExpected = """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: %s | Topic: %s | Methodology: %s
                Title: "%s"
                Bot generation: %d
                %s%s%s
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                %s

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                %s

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                %s

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                %s%s
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)","findings":"2-3 sentences \
                interpreting the pattern or trend — its significance and what it suggests; \
                cite a prior paper by ID if relevant",%s,\
                "followUp":{"param1":N,"param2":X.X,"predictedScore":0.30},\
                "confidenceScore":0.75,"cited_paper_ids":["P-xxxx-1"]}

                followUp rules: ONLY include followUp if the prior papers reveal a specific \
                unexplored parameter region worth targeting next. param1 is an integer 10–99 \
                (shifts the search starting position). param2 is a decimal 1.0–9.0 (scales depth). \
                predictedScore is your numeric prediction (0.0–1.0) for what quality the follow-up \
                result will achieve. If no clear follow-up region emerges, OMIT the followUp field entirely.
                confidenceScore rules: 0.0–1.0; your assessment of how well-supported and robust \
                these findings are (0.5 = neutral, 1.0 = highly confident, 0.0 = very uncertain). \
                For a null result, confidenceScore should reflect confidence that the result is \
                genuinely null, not confidence in a positive claim.
                cited_paper_ids rules: array of Paper IDs from the prior papers list above that \
                you directly referenced in findings. Use exact IDs (format P-xxxx-N). Empty array if none.
                Optionally — instead of (or in addition to) cited_paper_ids, you may emit a \
                cited_papers array with typed relationships. Each entry: {"id":"P-xxxx-N",\
                "type":"SUPPORTS|CONTRADICTS|EXTENDS|REPLICATES|CRITICIZES|DERIVES_FROM",\
                "confidence":0.0-1.0}. Use SUPPORTS when your findings confirm the cited paper; \
                CONTRADICTS when they disagree; EXTENDS when you build on it without disputing; \
                REPLICATES when you ran the same experiment; CRITICIZES when you question its method; \
                DERIVES_FROM when you inherit its framework. Only include cited_papers when you have \
                high enough confidence to pick a specific type — otherwise stick with cited_paper_ids.
                Optional formal_findings array — emit one entry per quantitative relationship the \
                results support. Each entry: {"relation_type":"PROPORTIONAL|INVERSE|THRESHOLD|NULL",\
                "dependent_variable":"name","independent_variables":["x1","x2"],\
                "equation_form":"optional symbolic form","claim_status":"SUPPORTED|PARTIAL|REFUTED|OBSERVATIONAL",\
                "confidence":0.0-1.0,"valid_region_min":N,"valid_region_max":M}. Use PROPORTIONAL/INVERSE \
                only when the relationship is clearly monotone; THRESHOLD for step changes; NULL when the \
                paper found no reliable relationship. Omit the array when nothing is structurally extractable.
                All text values under 120 words each. Plain prose inside JSON strings only — \
                no bullet points, no sub-headers, no markdown formatting.
                """.formatted(
                        domain, topicName, methodology,
                        title, generation,
                        paramsLine, followUpNote, anomalyNote,
                        rawFindings,
                        scaffoldHyp, scaffoldCon,
                        priorPapers, preRegSection,
                        conclusionSpec);

        assertThat(registryRendered).isEqualTo(inlineExpected);
    }

    // Phase 7 F2 — v3 structured-findings variants must be registered alongside
    // v1 and v2-with-discussion, and render with the four extra slots
    // ({{ maxComputations }}, {{ findingsSpec }}, {{ findingsInstructions }},
    // plus all of v1's slots).
    @Test
    void v3StructuredFindingsVariantsAreRegisteredAndRender() {
        PromptTemplateRegistry r = freshSeededRegistry();

        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",              "PHYSICS");
        params.put("topicName",           "RLC Damping Transition");
        params.put("methodology",         "Simulation");
        params.put("title",               "Title");
        params.put("generation",          "3");
        params.put("paramsLine",          "");
        params.put("followUpNote",        "");
        params.put("anomalyNote",         "");
        params.put("rawFindings",         "raw");
        params.put("scaffoldHyp",         "Hypo");
        params.put("scaffoldCon",         "Concl");
        params.put("priorPapers",         "  (none)");
        params.put("preRegSection",       "");
        params.put("conclusionSpec",      "\"conclusion\":\"…\"");
        params.put("maxComputations",     "2");
        params.put("findingsSpec",        "\"computation_1\":\"<paragraph 1>\",\"computation_2\":\"<paragraph 2>\"");
        params.put("findingsInstructions","Findings rules: emit exactly 2 non-empty paragraphs.");

        String body = r.render("narration.primary", "v3-structured-findings", params);
        assertThat(body)
                .contains("\"findings\":{ \"computation_1\":\"<paragraph 1>\",\"computation_2\":\"<paragraph 2>\" }")
                .contains("Findings rules: emit exactly 2 non-empty paragraphs.")
                // Updated 2026-05-21: prompt now says "up to N" + "one block per call you
                // actually completed" instead of pre-promising N blocks. Catches the
                // P-a328-824/836/860 pattern where the LLM emits 5 blocks for 1 tool call.
                .contains("represents up to 2 independent computation(s)")
                .contains("one short prose paragraph in `findings` for each tool call you actually completed")
                .contains("Do NOT pad with placeholder blocks")
                .doesNotContain("{{ findingsSpec }}");

        String bodyDisc = r.render("narration.primary",
                "v3-structured-findings-with-discussion", params);
        assertThat(bodyDisc)
                .contains("\"findings\":{ \"computation_1\":\"<paragraph 1>\",\"computation_2\":\"<paragraph 2>\" }")
                .contains("\"discussion\":")
                .contains("\"related_work\":")
                .doesNotContain("{{ findingsSpec }}");
    }

    // ---- Phase 7 F7.1: panel review prompt template ----

    @Test
    void f7_1_panelReviewTemplateRendersAllSlotsAndContainsSchema() {
        PromptTemplateRegistry r = freshSeededRegistry();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",         "PHYSICS");
        params.put("topicName",      "RLC Damping Transition");
        params.put("hypothesis",     "Resonance near 3500 Hz.");
        params.put("findings",       "Computed Q = 42.30 at the resonance peak.");
        params.put("conclusion",     "Underdamped regime confirmed.");
        params.put("toolCallTrace",
                "[{\"functionName\":\"run_param_sweep\",\"argsJson\":\"{}\","
                        + "\"resultJson\":\"{\\\"peak_q\\\":42.30}\",\"round\":0}]");

        String body = r.render(PromptTemplateIds.NARRATION_PANEL_REVIEW_ID, params);

        // Slots are interpolated.
        assertThat(body)
                .contains("PHYSICS")
                .contains("RLC Damping Transition")
                .contains("Resonance near 3500 Hz.")
                .contains("Q = 42.30")
                .contains("Underdamped regime confirmed.")
                .contains("run_param_sweep")
                .doesNotContain("{{ domain }}")
                .doesNotContain("{{ toolCallTrace }}");

        // Output schema is part of the prompt so reviewers know the shape.
        assertThat(body)
                .contains("\"grades\"")
                .contains("\"honesty\"")
                .contains("\"completeness\"")
                .contains("\"rigor\"")
                .contains("\"novelty\"")
                .contains("\"cited_calls\"")
                .contains("\"omitted_relevant\"")
                .contains("\"contradictions\"")
                .contains("\"trace_idx\"")
                .contains("\"vs_prose\"")
                .contains("\"recommendations\"")
                // Phase 23.6 panel-emission slice — evidence_label field.
                .contains("\"evidence_label\"")
                .contains("THEOREM")
                .contains("EMPIRICAL_RESULT")
                .contains("CONJECTURE")
                .contains("UNEXPLAINED_OBSERVATION");
    }

    @Test
    void f7_1_panelReviewActiveVariantIsV1() {
        PromptTemplateRegistry r = freshSeededRegistry();
        PromptTemplate active = r.pickActive(PromptTemplateIds.NARRATION_PANEL_REVIEW_ID).orElseThrow();
        assertThat(active.version()).isEqualTo(PromptTemplateIds.NARRATION_PANEL_REVIEW_VERSION);
    }

    // ---- Phase 23.6 conjecture-investigation prompt ----

    @Test
    void phase23_6_conjectureTemplateRegisteredAndRendersAllSlots() {
        PromptTemplateRegistry r = freshSeededRegistry();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",     "MATHEMATICS");
        params.put("topicName",  "conjecture/abundant_density");
        params.put("hypothesis", "abundant_density(n) <= 0.2515 for all n >= 100.");
        params.put("findings",   "Mined across 8 windows; p99Slack -0.012; no refutation.");
        params.put("conclusion", "Bound holds in observed regimes; held-out verification recommended.");

        String body = r.render(
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID, params);

        // Slots interpolated.
        assertThat(body)
                .contains("MATHEMATICS")
                .contains("conjecture/abundant_density")
                .contains("abundant_density(n) <= 0.2515")
                .contains("Mined across 8 windows")
                .contains("held-out verification recommended")
                .doesNotContain("{{ domain }}")
                .doesNotContain("{{ hypothesis }}")
                .doesNotContain("{{ findings }}")
                .doesNotContain("{{ conclusion }}");
    }

    @Test
    void phase23_6_conjectureTemplateInstructsMechanismAndAdversarialCheck() {
        PromptTemplateRegistry r = freshSeededRegistry();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",     "MATHEMATICS");
        params.put("topicName",  "conjecture/x");
        params.put("hypothesis", "h");
        params.put("findings",   "f");
        params.put("conclusion", "c");
        String body = r.render(
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID, params);

        // The LabelFloor admits EMPIRICAL_RESULT only when prose contains
        // "mechanism" AND ("adversarial check" OR "verify_conjecture"). The
        // template must instruct the narrator to use those exact tokens; pin
        // each one verbatim so a future prompt rewrite that softens the
        // wording is caught by this test.
        assertThat(body)
                .contains("## Mechanism")
                .contains("## Adversarial Check")
                .contains("verify_conjecture")
                .contains("no mechanism identified");  // honest-fallback phrasing
    }

    @Test
    void rec7_auditTemplateFlagsCircularVerificationUnderPhysicalMechanism() {
        // rec #7 (2026-05-28) — the active audit template must name the
        // circular / tautological verification pattern (P-a328-997: λ_max=b/T
        // "verified" against b/T) so the first audit pass flags it and it routes
        // through the existing default-on physical_mechanism→REJECTED gate.
        PromptTemplateRegistry r = freshSeededRegistry();
        PromptTemplate active = r.pickActive(PromptTemplateIds.NARRATION_AUDIT_ID).orElseThrow();
        String body = active.body();
        assertThat(body)
                .contains("CIRCULAR / TAUTOLOGICAL")
                .contains("compares a derived quantity to its own")   // single-line (text-block wraps after "own")
                .contains("independent measurement, prediction, or dataset")
                .contains("physical_mechanism");      // stays on the existing gate's dimension
    }

    @Test
    void sidecar7a_professorPromptWarnsAgainstMathematicaGlobals() {
        // §7a — the LLM kept reaching for Mathematica-style globals in run_symbolic
        // (PI/PLANCK_H/Solve), the top recurring sidecar eval errors. The active
        // professor system prompt must steer it to Julia equivalents.
        PromptTemplateRegistry r = freshSeededRegistry();
        String body = r.pickActive(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID)
                .orElseThrow().body();
        assertThat(body)
                .contains("Julia, NOT Mathematica")
                .contains("never `PI`")
                .contains("no `PLANCK_H`")
                .contains("no `Solve[...]`");
    }

    @Test
    void phase23_6_conjectureTemplateActiveAtV1() {
        PromptTemplateRegistry r = freshSeededRegistry();
        PromptTemplate active = r.pickActive(
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID).orElseThrow();
        assertThat(active.version())
                .isEqualTo(PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_VERSION);
    }

    private static PromptTemplateRegistry freshSeededRegistry() {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.seedDefaults();   // package-private, invoked here exactly like @PostConstruct would.
        return r;
    }
}
