package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4c Piece 2 — the registry that {@link PromptTemplate}s are resolved
 * and rendered through. Replaces inline {@code String.format("...", args)}
 * call sites with {@code registry.render(id, namedParams)}.
 *
 * Today, exactly one variant per template ships (every id has its {@code v1}
 * registered in {@link #seedDefaults()}). Piece 3 adds the A/B and
 * epsilon-greedy selection logic per the locked decisions in
 * {@code phase-4c-piece-1-memo.md}; today's
 * {@link #pickActive(String)} hook just returns the last-registered variant
 * for an id so adding a future variant is a one-line registration call.
 *
 * Slot rendering is intentionally narrow — see {@link #render(String, Map)}
 * and {@link #renderTemplate(String, Map)} for the contract. The renderer
 * does not format numbers, evaluate expressions, or run loops; callers
 * pre-format anything beyond a stringification.
 *
 * Adding a new template is two lines:
 *   1. A constant pair in {@link PromptTemplateIds} (id + version).
 *   2. A {@code register(new PromptTemplate(id, version, body))} call in
 *      {@link #seedDefaults()}.
 * Migrating a callsite is one line: replace the inline
 * {@code String.format(...)} or {@code "...".formatted(...)} with
 * {@code registry.render(id, Map.of("name", value, ...))}.
 *
 * The body strings themselves remain in this file rather than in YAML / DB
 * to keep the prompt next to its doc-comment per the memo's "Java
 * initialiser" decision. A future move to external storage is a constructor
 * change that doesn't ripple into call sites.
 */
@Service
public class PromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRegistry.class);

    private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    private final Map<String, List<PromptTemplate>> variantsById = new HashMap<>();

    /**
     * Phase 4c Piece 3 PR1 — optional persistence sidecar. When wired
     * (Spring context with a JPA datasource), the registry idempotently
     * upserts every seeded template into the DB on startup so the
     * subsequent piece-3 PRs (counter writes, rollback judge) have a
     * row to attach to. Null in unit-test contexts that build the
     * registry via {@code new PromptTemplateRegistry()}; the upsert
     * code path is then skipped entirely.
     */
    @Autowired(required = false)
    private PromptTemplateVariantRepository variantRepo;

    /**
     * Phase 4c Piece 3 PR2 — epsilon-greedy selector flag. When false
     * (default), {@link #pickActive(String)} keeps the pre-piece-3
     * "last-registered wins" semantics so the registry is bit-identical
     * to today. When true AND ≥ 2 ACTIVE variants exist for the id AND
     * {@link #variantRepo} is wired, {@link #pickActive(String)} uses
     * epsilon-greedy selection: explore (uniform random) with
     * probability {@link #epsilon}, else exploit (highest success rate).
     *
     * <p>Default false because the piece-3 design doc names three
     * operator pre-conditions for activation: (a) an alternative
     * variant exists, (b) the revised ε / threshold are accepted,
     * (c) Phase 21's binary has run ≥ 1 week.
     */
    @Value("${app.research.prompt-template-epsilon-greedy.enabled:false}")
    private boolean epsilonGreedyEnabled;

    /**
     * Exploration probability when {@link #epsilonGreedyEnabled} is on.
     * Default 0.20 per the piece-3 design doc Decision 6 revision
     * (corpus rate is ~38 papers/day; ε=0.10 was too slow). Operator
     * can override via {@code app.research.prompt-template-epsilon-greedy.epsilon}.
     */
    @Value("${app.research.prompt-template-epsilon-greedy.epsilon:0.20}")
    private double epsilon;

    /**
     * RNG used by epsilon-greedy. Package-private so tests can swap in
     * a seeded {@link Random} for deterministic assertions. {@code null}
     * when {@link #epsilonGreedyEnabled} is false (the rng path is
     * skipped before {@code rng.nextDouble()} is reached).
     */
    Random rng = new Random();

    @PostConstruct
    public void seedDefaults() {
        // FORMULATION_NOVELTY — picks a novelty score for a (domain, topic) so the
        // formulation prompt can warn the bot when the topic is saturating. Single
        // round-trip, JSON-shaped reply. Migrated in Piece 2's first wave.
        register(new PromptTemplate(
                PromptTemplateIds.FORMULATION_NOVELTY_ID,
                PromptTemplateIds.FORMULATION_NOVELTY_VERSION,
                """
                You are assessing how much computational novelty remains in a research topic.

                Domain: {{ domain }} | Topic: {{ topicName }}
                Total papers published in this topic so far: {{ totalCount }}

                Most recent papers (findings summaries):
                {{ recentPapers }}
                Rate how much unexplored territory remains: 1.0 = completely fresh, 0.0 = fully saturated.
                Note which parameter regions or angles have NOT been tried yet.

                Reply with ONLY this JSON — no other text:
                {"novelty_score":0.7,"coverage_note":"brief note on what has been covered","unexplored_hint":"specific suggestion for an unexplored region or angle; empty string if no clear gap"}
                """));

        // MENTORING_GENOME — fires after a high-quality publication. Asks the LLM
        // to suggest 1–2 genome edits plus an optional research directive based on
        // the bot's own recent work and peer work in the same domain. Cross-domain
        // anomalous findings are surfaced so the advisor can suggest structural
        // transfer. The example directives inside the prompt are hard-coded
        // exemplars — the LLM is told not to copy them.
        // The pre-carve template used the literal "%%" String.format escape for a
        // single "%" in the body; the registry renderer doesn't run printf-style
        // escaping, so the migrated body uses a single literal "%" verbatim. The
        // equivalence test pins this; both renderings produce the same final text.
        register(new PromptTemplate(
                PromptTemplateIds.MENTORING_GENOME_ID,
                PromptTemplateIds.MENTORING_GENOME_VERSION,
                """
                You are a research advisor in a simulation. A researcher bot has just published \
                notable work and is eligible for a mentoring session.

                Researcher: {{ botId8 }}  |  Domain: {{ domain }}  |  Methodology: {{ methodology }}
                Generation: {{ generation }}  (evolution and mentoring cycles completed)

                Research personality (genome):
                  Step size:             {{ w0 }}  — spacing between candidate regions (0.05=narrow, 1.0=wide)
                  Restart willingness:   {{ w1 }}  — reserved, not yet active
                  Candidate breadth:     {{ w2 }}  — regions explored per paper (20–80; key quality driver)
                  Peer tolerance:        {{ w3 }}  — reviewer strictness (low=strict 0.02, high=lenient 0.12)
                  Acceptance threshold:  {{ w4 }}  — reserved for future gating
                  Curiosity:             {{ explorationRate }}  — overall openness to new directions

                Parameter search region (learned from publication history):
                  Center: [param1={{ paramCenter1 }}, param2={{ paramCenter2 }}]  — where they focus searches
                  Radius: [±{{ paramRadius1 }}, ±{{ paramRadius2 }}]              — how broadly they explore around that center
                  (narrowing radius = deepening specialisation; wider = more exploratory)

                Career so far: {{ publishedCount }} papers published, {{ rejectedCount }} drafts not accepted
                Their own recent work:
                {{ ownWork }}
                Recent work by peers in the same domain:
                {{ peerWork }}{{ crossDomainAnomalies }}
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
                  {"description":"abundant numbers in range 200-400","rangeStart":200,"rangeEnd":400,"predicate":"isAbundant","metric":"fraction_satisfying","baseline":0.25,"hypothesis":"About 25% of integers in 200-400 are abundant, consistent with the known asymptotic density."}
                  {"description":"mean Collatz depth for happy primes near 800","rangeStart":780,"rangeEnd":950,"predicate":"isPrime AND isHappy","metric":"mean_of:collatzSteps","baseline":75.0,"hypothesis":"Happy primes near 800 require about 75 Collatz steps to terminate, deeper than the general average."}
                  {"description":"perfect or abundant numbers below 300","rangeStart":2,"rangeEnd":302,"predicate":"isAbundant OR isPerfect","metric":"fraction_satisfying","baseline":0.24,"hypothesis":"Roughly 24% of integers below 302 are abundant or perfect."}
                """));

        // NARRATION_PRIMARY — the main first-pass narration. Largest template
        // in the registry by far. Three optional context blocks
        // ({{ paramsLine }}, {{ followUpNote }}, {{ anomalyNote }}) follow the
        // pre-carve %s%s%s pattern; {{ preRegSection }} folds the optional
        // pre-registration block; {{ conclusionSpec }} is one of two JSON
        // shapes depending on whether the result was classified NULL.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PRIMARY_ID,
                PromptTemplateIds.NARRATION_PRIMARY_VERSION,
                """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: {{ domain }} | Topic: {{ topicName }} | Methodology: {{ methodology }}
                Title: "{{ title }}"
                Bot generation: {{ generation }}
                {{ paramsLine }}{{ followUpNote }}{{ anomalyNote }}
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                {{ rawFindings }}

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                {{ scaffoldHyp }}

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                {{ scaffoldCon }}

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                {{ priorPapers }}{{ preRegSection }}
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)","findings":"2-3 sentences \
                interpreting the pattern or trend — its significance and what it suggests; \
                cite a prior paper by ID if relevant",{{ conclusionSpec }},\
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
                """));

        // Phase 23.6 — NARRATION_CONJECTURE_INVESTIGATION. Specialized prompt for
        // PaperKind.CONJECTURE_INVESTIGATION papers. The mining engine pre-fills
        // the draft's hypothesis (verbatim bound), findings (mining history), and
        // conclusion (recommended language); the narrator's job is to expand into
        // a structured investigation with Mechanism + Adversarial Check sections.
        //
        // The LabelFloor's ceiling for EMPIRICAL_RESULT requires the literal
        // string "mechanism" AND either "adversarial check" OR a
        // "verify_conjecture" tool-call trace in the paper text. The prompt
        // explicitly tells the narrator to use those exact tokens so the floor
        // will admit the EMPIRICAL_RESULT proposal that the panel earns by
        // majority. THEOREM remains unreachable on this path — the floor demands
        // Q.E.D./∎/## Proof markers, which a non-proof-engine narrator should
        // not produce.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID,
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_VERSION,
                """
                You are writing an investigation of a mined mathematical conjecture. \
                The miner pre-registered the conjecture and surfaced supporting evidence; \
                your job is to write the paper that argues whether the bound holds and why.

                Domain: {{ domain }} | Topic: {{ topicName }}

                Conjecture (verbatim — do not paraphrase, restate, or generalize):
                {{ hypothesis }}

                Mining history (the substrate the miner consumed):
                {{ findings }}

                Pre-registered conclusion shape (rewrite in your own words, preserving the \
                cautious tone — the miner has not seen a counterexample but the bound is \
                not formally proved):
                {{ conclusion }}

                Required structure for the `findings` field — your prose MUST contain:

                  1. A "## Mechanism" section: propose a structural reason the bound holds. \
                     BEGIN the section with a line exactly of the form \
                     "Mechanism kind: <one of: heuristic_derivation | asymptotic_intuition | \
                     probabilistic_model | extremal_construction | analogy_to_known_theory | \
                     none>", then give the argument IN THAT MODE — not a paraphrase of the \
                     inequality. Examples per kind: \
                     asymptotic_intuition — "the LHS counts X-prime factorizations, bounded \
                     by a Stirling-like approximation of …"; \
                     analogy_to_known_theory — "this is the Mertens bound restricted to …"; \
                     probabilistic_model — "modelling ω(n) as Poisson(log log n), the bound \
                     is the … tail"; \
                     extremal_construction — "the tight case is the primorial, where …"; \
                     heuristic_derivation — "summing the per-prime contribution gives …". \
                     Pick the kind that GENUINELY fits and argue in it; do NOT dress \
                     hand-waving up as a mode it isn't. If you cannot identify a structural \
                     mechanism, use "Mechanism kind: none" and the words "no mechanism \
                     identified" — honesty beats fabricated reasoning. (NOTE: this tag is \
                     self-declared and unverified — the adversarial check tests the BOUND, \
                     not the mechanism; per-mode verification is future work.)

                  2. A "## Adversarial Check" section: invoke the `verify_conjecture` tool \
                     on at least one held-out window the miner did not sample. Report the \
                     worst-margin witness, even when the bound HELD. If the tool refutes \
                     the bound on a held-out slice, narrate the counterexample — that is the \
                     paper. When you quote the worst-margin witness, take the observed LHS \
                     value from the SAME per-window row (same `start=`) as that worst \
                     margin — the trace lists `lhs` and `margin` side by side per window; \
                     never pair the worst margin with a different window's `lhs`.

                The reviewer panel will judge whether your `findings` actually contains a \
                mechanism and an adversarial check. The text-heuristic floor blocks any \
                EMPIRICAL_RESULT label on prose missing the words "mechanism" AND \
                ("adversarial check" OR a verify_conjecture tool-call trace).

                Reply with ONLY this JSON — no markdown outside the JSON strings:
                {"hypothesis":"the bound, copied verbatim from above",\
                "findings":"prose with ## Mechanism and ## Adversarial Check sections; \
                under 250 words; include the literal phrase 'verify_conjecture' when you \
                invoke the tool",\
                "conclusion":"1-2 sentences: state whether the held-out check refuted, \
                tightened, or left the conjecture intact; propose the next mining direction",\
                "confidenceScore":0.50,"cited_paper_ids":[]}

                confidenceScore rules: 0.0–1.0. For a held conjecture with no refutation, \
                pick ~0.6–0.8 reflecting how tight the worst-margin witness was. For a \
                refuted conjecture, pick 0.2–0.4 — refutation is informative but the paper \
                is making a negative claim.

                Do not emit followUp, formal_findings, or cited_papers — the conjecture \
                miner manages its own queue and the bound IS the formal finding.
                """));

        // Phase 7 F2 — v3-structured-findings variant of NARRATION_PRIMARY. The
        // {{ findingsSpec }} slot carries a comma-separated computation_N JSON
        // shape (rendered Java-side) so the narrator emits one prose block per
        // computation. The {{ findingsInstructions }} slot carries the prose-
        // length and equation-reference rules for each block. Used when
        // app.research.structured-findings.enabled is on AND the discussion
        // flag is off; v1 stays untouched for the flag-off bit-identical path.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PRIMARY_ID,
                "v3-structured-findings",
                """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: {{ domain }} | Topic: {{ topicName }} | Methodology: {{ methodology }}
                Title: "{{ title }}"
                Bot generation: {{ generation }}
                {{ paramsLine }}{{ followUpNote }}{{ anomalyNote }}
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                {{ rawFindings }}

                The raw output above represents up to {{ maxComputations }} independent computation(s) \
                you may invoke via tools. Emit exactly one short prose paragraph in `findings` for \
                each tool call you actually completed during this session, keyed by 1-based call \
                index. Do NOT pad with placeholder blocks for tool calls that didn't run — fewer \
                blocks is the honest representation when a call was rejected, timed out, or never \
                attempted. The block-count guard demotes papers whose block count exceeds the \
                trace size.

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                {{ scaffoldHyp }}

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                {{ scaffoldCon }}

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                {{ priorPapers }}{{ preRegSection }}
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)",\
                "findings":{ {{ findingsSpec }} },\
                {{ conclusionSpec }},\
                "followUp":{"param1":N,"param2":X.X,"predictedScore":0.30},\
                "confidenceScore":0.75,"cited_paper_ids":["P-xxxx-1"]}

                {{ findingsInstructions }}
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
                All text values plain prose inside JSON strings only — no markdown, no bullet points.
                """));

        // Phase 7 F2 — v3-structured-findings-with-discussion variant. Same
        // structured-findings shape, plus the Phase 6 D2 discussion + related_work
        // keys. Used when BOTH structured-findings.enabled AND discussion-section.enabled
        // are on.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PRIMARY_ID,
                "v3-structured-findings-with-discussion",
                """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: {{ domain }} | Topic: {{ topicName }} | Methodology: {{ methodology }}
                Title: "{{ title }}"
                Bot generation: {{ generation }}
                {{ paramsLine }}{{ followUpNote }}{{ anomalyNote }}
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                {{ rawFindings }}

                The raw output above represents up to {{ maxComputations }} independent computation(s) \
                you may invoke via tools. Emit exactly one short prose paragraph in `findings` for \
                each tool call you actually completed during this session, keyed by 1-based call \
                index. Do NOT pad with placeholder blocks for tool calls that didn't run — fewer \
                blocks is the honest representation when a call was rejected, timed out, or never \
                attempted. The block-count guard demotes papers whose block count exceeds the \
                trace size.

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                {{ scaffoldHyp }}

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                {{ scaffoldCon }}

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                {{ priorPapers }}{{ preRegSection }}
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)",\
                "findings":{ {{ findingsSpec }} },\
                {{ conclusionSpec }},\
                "discussion":"2-3 sentences interpreting what the result implies for the broader \
                research direction; honest about limitations and what remains uncertain",\
                "related_work":"1-2 sentences pointing to adjacent prior work — either the \
                cited Paper IDs above or the established literature for this topic; acknowledge \
                what is already known","followUp":{"param1":N,"param2":X.X,"predictedScore":0.30},\
                "confidenceScore":0.75,"cited_paper_ids":["P-xxxx-1"]}

                {{ findingsInstructions }}
                followUp / confidenceScore / cited_paper_ids rules — same as v1.
                discussion: under 100 words; honest about what stays unsettled; do not over-claim novelty.
                related_work: under 80 words; ground the work against priors. If no priors apply
                explicitly say so rather than fabricating references.
                All text values plain prose inside JSON strings only — no markdown.
                """));

        // Phase 6 D2 — v2-with-discussion variant of NARRATION_PRIMARY. Adds a
        // {{ discussionInstruction }} slot near the JSON shape spec so the
        // narrator emits two extra keys (discussion, related_work). Used when
        // app.research.discussion-section.enabled is on; v1 stays untouched.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PRIMARY_ID,
                "v2-with-discussion",
                """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: {{ domain }} | Topic: {{ topicName }} | Methodology: {{ methodology }}
                Title: "{{ title }}"
                Bot generation: {{ generation }}
                {{ paramsLine }}{{ followUpNote }}{{ anomalyNote }}
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                {{ rawFindings }}

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                {{ scaffoldHyp }}

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                {{ scaffoldCon }}

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                {{ priorPapers }}{{ preRegSection }}
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)","findings":"2-3 sentences \
                interpreting the pattern or trend — its significance and what it suggests; \
                cite a prior paper by ID if relevant",{{ conclusionSpec }},\
                "discussion":"2-3 sentences interpreting what the result implies for the broader \
                research direction; honest about limitations and what remains uncertain",\
                "related_work":"1-2 sentences pointing to adjacent prior work — either the \
                cited Paper IDs above or the established literature for this topic; acknowledge \
                what is already known","followUp":{"param1":N,"param2":X.X,"predictedScore":0.30},\
                "confidenceScore":0.75,"cited_paper_ids":["P-xxxx-1"]}

                followUp / confidenceScore / cited_paper_ids rules — same as v1.
                discussion: under 100 words; honest about what stays unsettled; do not over-claim novelty.
                related_work: under 80 words; ground the work against priors. If no priors apply
                explicitly say so rather than fabricating references.
                All text values plain prose inside JSON strings only — no markdown.
                """));

        // NARRATION_REFINEMENT — refinement-loop "should I run another step"
        // decider. Single round-trip with a small JSON reply
        // ({"action":"refine|done", ...}).
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_REFINEMENT_ID,
                PromptTemplateIds.NARRATION_REFINEMENT_VERSION,
                """
                You are guiding a multi-step computational investigation. \
                The researcher has {{ iterContext }} — decide whether one more focused \
                computation would add a meaningfully distinct data point.

                Domain: {{ domain }} | Topic: {{ topicName }} | Methodology: {{ methodology }}
                Computation parameters: starting position {{ param1 }}, scale {{ param2 }}
                Computations so far:
                {{ accumulatedFindings }}

                A good refinement target narrows to a sub-region where something \
                interesting occurred, tests a boundary condition, or checks an \
                adjacent parameter scale. It must produce quantitatively different \
                numbers — not a repeat of what is already shown.

                Remaining iterations allowed: {{ remainingIterations }} of {{ maxIterations }}.

                Reply with ONLY one of these JSON forms — no other text:
                {"action":"refine","param1":N,"param2":X.X,"rationale":"one sentence — what this probes and why"}
                {"action":"done"}

                param1 int 10–99. param2 decimal 1.0–9.0. \
                Choose "done" if the finding is already well-characterised or \
                no clear target remains.
                """));

        // NARRATION_REVISION — two variants registered under the same id. The
        // "from-synthesis" variant is used when a tool-calling professor agent
        // produced a synthesis output; the "from-critique" variant is the
        // standard path that takes a critique's weakness list plus an optional
        // professor-prescribed experiment block. Variants pick the same id so
        // Piece 3 can A/B them as alternative phrasings of the same role; the
        // current pickActive returns the last-registered variant, so
        // production callsites pass an explicit version string to disambiguate.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_REVISION_ID,
                "v1-from-critique",
                """
                You are a student revising your computational research paper based on your \
                professor's feedback.

                Domain: {{ domain }} | Topic: {{ topicName }}

                YOUR ORIGINAL DRAFT:
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                PROFESSOR'S FEEDBACK:
                {{ weaknessList }}{{ experimentSection }}
                Revise your paper. Keep correct content unchanged. Qualify over-claims. \
                If new experimental evidence is shown above, incorporate it naturally — \
                do not fabricate results beyond what is stated. Do not change physical quantities.

                Reply with ONLY this JSON — no markdown, no preamble:
                {"hypothesis":"revised hypothesis","findings":"revised 2-3 sentence findings",{{ conclusionSpec }},\
                "confidenceScore":0.75,"cited_paper_ids":[]}

                All text values plain prose, under 120 words each.
                """));
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_REVISION_ID,
                "v1-from-synthesis",
                """
                You are a student revising your computational research paper based on your professor's review.
                Incorporate the professor's experimental findings naturally into the revised text.
                Address the identified weaknesses. Do not introduce new claims beyond what the data supports.

                {{ paramsLine }}Domain: {{ domain }} | Topic: {{ topicName }}

                PROFESSOR'S REVIEW:
                {{ professorSynthesis }}

                CURRENT DRAFT:
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                Rewrite the paper as:
                {"hypothesis":"...",
                 "findings":"2-3 sentences with appropriate precision; incorporate new experimental evidence",
                 {{ conclusionSpec }},
                 "confidenceScore":0.0-1.0}
                Reply with ONLY the JSON.
                """));
        // Phase 6 D1.3 — v2-from-synthesis variant carrying a {{ literatureContext }}
        // slot. The synthesis path is what the professor-tool flow uses; the
        // student revision step also gets the literature context so the revised
        // text can cite the closed-form.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_REVISION_ID,
                "v2-from-synthesis-with-literature",
                """
                You are a student revising your computational research paper based on your professor's review.
                Incorporate the professor's experimental findings naturally into the revised text.
                Address the identified weaknesses. Do not introduce new claims beyond what the data supports.

                {{ paramsLine }}Domain: {{ domain }} | Topic: {{ topicName }}
                {{ literatureContext }}
                PROFESSOR'S REVIEW:
                {{ professorSynthesis }}

                CURRENT DRAFT:
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                Rewrite the paper as:
                {"hypothesis":"...",
                 "findings":"2-3 sentences with appropriate precision; incorporate new experimental evidence",
                 {{ conclusionSpec }},
                 "confidenceScore":0.0-1.0}
                Reply with ONLY the JSON.
                """));

        // NARRATION_CRITIQUE — self-critique before revision. The {{ experimentSpec }}
        // composite slot folds in one of three pre-carve branches: a "dynamic +
        // param" block when the topic supports number-theoretic experiments, a
        // param-only block when only param-based computation is possible, or a
        // single-line "no additional computation" fallback. The
        // {{ preRegAndClassLines }} composite slot folds the optional
        // pre-registration and result-classification lines, matching the
        // pre-carve %s%s pattern.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_CRITIQUE_ID,
                PromptTemplateIds.NARRATION_CRITIQUE_VERSION,
                """
                You are a professor reviewing a student's computational research paper.

                Domain: {{ domain }} | Topic: {{ topicName }}
                {{ preRegAndClassLines }}
                Student's draft:
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                Provide two types of feedback:
                1. Up to 2 textual weaknesses (overclaiming, missing qualifications, logic gaps,
                   conclusions that contradict the pre-registered prediction).
                2. ONE prescribed experiment — a specific computation that would directly address
                   a real weakness in the paper. Only prescribe if it would materially change
                   what can be claimed. Omit if the paper is already well-supported.

                {{ experimentSpec }}
                Reply with ONLY this JSON — no other text:
                {"weaknesses":["w1","w2"],"experimental_gap":{...}}
                Omit experimental_gap entirely if no computation would help.
                Return weaknesses as an empty array if the draft is sound.
                """));

        // NARRATION_STATISTICAL_FRAMING — second-pass editor that takes raw
        // computational findings and re-shapes them into precision-aware
        // scientific prose. Single round-trip, free-form text reply (NOT JSON).
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_ID,
                PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_VERSION,
                """
                You are a scientific editor improving precision reporting in a computational paper.

                Domain: {{ domain }} | Topic: {{ topicName }}

                Raw computational output:
                {{ rawFindings }}

                Rewrite this for the findings section of a scientific paper. Rules:
                1. Replace exact decimals beyond 3 significant figures with rounded values.
                2. Add appropriate uncertainty qualifiers: "approximately", "roughly", "within ~X%", "±" estimates.
                3. For very small (<0.001) or very large (>10000) values use scientific notation.
                4. Do NOT change any facts, trends, relationships, or physical quantities — only the precision framing.
                5. Keep the same length and structure. Plain prose only, no bullet points, no headers.

                Reply with ONLY the rewritten findings text — no preamble, no JSON, no explanation.
                """));

        // NARRATION_AUDIT — fast quality audit that flags structural issues
        // in a draft (cross-domain mapping, degenerate results, duplicates,
        // missing uncertainty, overclaiming). Reply is a small JSON {gaps:[…]}.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_AUDIT_ID,
                PromptTemplateIds.NARRATION_AUDIT_VERSION,
                """
                Assess this computational research paper for quality gaps.
                Only flag material issues. Return an empty "gaps" array if the paper is fundamentally sound.

                Domain: {{ domain }} | Topic: {{ topicName }} | Classification: {{ classification }}
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                Check only these dimensions (skip if not problematic):
                - physical_mechanism: cross-domain mapping is purely definitional with no causal justification
                - degenerate_result: reported best result is a trivial edge case (e.g. zero/null/empty)
                - duplicate_computation: two or more computations tested identical parameter values
                - uncertainty: quantitative claims lack uncertainty language or error bounds
                - overclaiming: conclusion asserts more than the data demonstrates

                Reply with ONLY valid JSON:
                {"gaps":[{"dimension":"...","severity":"critical|moderate","description":"one sentence"}]}
                """));

        // Phase 7 G7 — v2 adds `boundary_answer` to catch papers whose best result
        // sits exactly at a boundary of the swept parameter range (e.g. Vf=1.00 on
        // a Vf∈[0,1] sweep, θ=90° on an angle survey). Last-registered wins, so
        // the audit pipeline picks this up without a flag change; v1 stays in the
        // registry for historical provenance.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_AUDIT_ID,
                "v2-with-boundary-answer",
                """
                Assess this computational research paper for quality gaps.
                Only flag material issues. Return an empty "gaps" array if the paper is fundamentally sound.

                Domain: {{ domain }} | Topic: {{ topicName }} | Classification: {{ classification }}
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}

                Check only these dimensions (skip if not problematic):
                - physical_mechanism: cross-domain mapping is purely definitional with no causal justification
                - degenerate_result: reported best result is a trivial edge case (e.g. zero/null/empty)
                - boundary_answer: reported best parameter value sits exactly at a boundary of the swept
                  range (e.g. volume fraction Vf=1.00 on a Vf∈[0,1] sweep, angle θ=90° on a [0°,90°]
                  survey, or any "best at the edge" answer that indicates the true optimum is outside
                  the searched region rather than inside it). Flag as moderate if the boundary value
                  is the answer but the conclusion does not acknowledge the range-saturation; flag as
                  critical if the conclusion treats the boundary value as an interior optimum.
                - duplicate_computation: two or more computations tested identical parameter values
                - uncertainty: quantitative claims lack uncertainty language or error bounds
                - overclaiming: conclusion asserts more than the data demonstrates

                Reply with ONLY valid JSON:
                {"gaps":[{"dimension":"...","severity":"critical|moderate","description":"one sentence"}]}
                """));

        // Phase 10 — v3 extends physical_mechanism with anchored examples of
        // known-bad cross-domain mappings. The May 2026 corpus had two papers
        // (P-a328-345 "Collatz depth → RLC inductance" and P-a328-352
        // "prime gaps → CTE / crystal defect spacing") that passed audit
        // with 0gaps despite being canonical non-sequiturs in the rubric.
        // The audit LLM evidently struggles to recognise the pattern without
        // exemplars; spelling them out trades a few prompt tokens for a
        // reliable refusal. Last-registered wins so v3 becomes active.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_AUDIT_ID,
                "v3-with-nonsequitur-exemplars",
                """
                Assess this computational research paper for quality gaps.
                Only flag material issues. Return an empty "gaps" array if the paper is fundamentally sound.

                Domain: {{ domain }} | Topic: {{ topicName }} | Classification: {{ classification }}
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}
                Discussion (first ~150 words): {{ discussion }}

                Check only these dimensions (skip if not problematic):
                - physical_mechanism: cross-domain mapping is purely definitional with no causal
                  justification — there is no physical or mathematical channel that lets one quantity
                  influence the other beyond a coincidence of numeric values. ALWAYS flag as CRITICAL
                  when the mapping resembles any of these documented non-sequiturs:
                    • integer-sequence depth (Collatz, aliquot, happy-number) → physical resonance,
                      inductance, capacitance, oscillation frequency, or damping
                    • prime-number gap statistics → coefficient of thermal expansion, lattice spacing,
                      crystal-defect periodicity, or any mechanical/thermal property
                    • number-theoretic predicate (abundant, perfect, squarefree) → wavelength,
                      diffraction order, or other wave-mechanical quantity
                    • twin-prime constellation density → material modulus, fatigue exponent, or strain
                  These mappings have *no physical channel*: integers do not exert force or carry
                  energy, so any apparent correlation is numeric coincidence at the harness level, not
                  a discovered law. Reject regardless of how plausibly the Findings describe the
                  numerical agreement.
                  Phase 14 F6: ALSO flag physical_mechanism as CRITICAL when the stated Domain is NOT
                  INTERDISCIPLINARY and the Discussion or Conclusion drifts into vocabulary from a
                  different domain's mechanism (e.g. a MATHEMATICS paper that invokes Van der Waals,
                  Schrödinger, lattice strain, or other physical-science concepts with no causal
                  bridge from the Hypothesis). INTERDISCIPLINARY papers are exempt; they bridge
                  domains intentionally.
                  rec #7 (2026-05-28): ALSO flag physical_mechanism as CRITICAL when the paper's
                  "verification" is CIRCULAR / TAUTOLOGICAL — it computes a quantity from a formula
                  and then "checks" it by re-evaluating the SAME formula and comparing the two,
                  reporting the residual as if it were a physical deviation. Such a test cannot fail
                  by construction; the only non-zero deviation is numerical noise. Example
                  (P-a328-997): computing Wien's λ_max = b/T and comparing it to b/T, then reporting
                  the 0.499% "deviation" as a finding when an independent computation shows ~0.01%.
                  Flag whenever the hypothesis's test compares a derived quantity to its own
                  definition rather than to an independent measurement, prediction, or dataset.
                - degenerate_result: reported best result is a trivial edge case (e.g. zero/null/empty)
                - boundary_answer: reported best parameter value sits exactly at a boundary of the swept
                  range (e.g. volume fraction Vf=1.00 on a Vf∈[0,1] sweep, angle θ=90° on a [0°,90°]
                  survey, or any "best at the edge" answer that indicates the true optimum is outside
                  the searched region rather than inside it). Flag as moderate if the boundary value
                  is the answer but the conclusion does not acknowledge the range-saturation; flag as
                  critical if the conclusion treats the boundary value as an interior optimum.
                - duplicate_computation: two or more computations tested identical parameter values
                - uncertainty: quantitative claims lack uncertainty language or error bounds
                - overclaiming: conclusion asserts more than the data demonstrates
                - threshold_drift: the Hypothesis states a numeric tolerance / threshold (e.g. "within
                  1%", "≤ 5%", "below 196 kJ/mol") and the Conclusion either uses a *different* numeric
                  threshold for the same quantity, or declares "confirmed" while the actual deviation
                  is two or more orders of magnitude tighter/looser than predicted. Flag critical when
                  the threshold shifts by more than 2× between sections.

                Reply with ONLY valid JSON:
                {"gaps":[{"dimension":"...","severity":"critical|moderate","description":"one sentence"}]}
                """));

        // Phase 7 F7.1 — NARRATION_PANEL_REVIEW — multi-reviewer panel.
        //
        // The orchestrator (F7.2) sends this prompt to N different provider pools
        // in parallel. Each reviewer LLM gets the same input: the paper draft +
        // the full agentic-tool-call trace + the synthesis prose, and returns a
        // strict JSON object that downstream aggregation can median across.
        //
        // The {{ toolCallTrace }} slot is a JSON array — see PanelReview's record
        // shape; entries are 1-indexed in the rendered prompt so the reviewer can
        // reference cited / omitted / contradicting entries by ordinal.
        //
        // The reviewer is told *not* to invent contradictions to look thorough.
        // Inter-reviewer agreement is the load-bearing signal in F7.2; noisy
        // reviewers self-correct via the median once enough reviewers participate.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PANEL_REVIEW_ID,
                PromptTemplateIds.NARRATION_PANEL_REVIEW_VERSION,
                """
                You are a skeptical co-reviewer with access to the complete computational record
                behind this research paper. The author has chosen which results to highlight in
                the prose; your job is to assess whether that selection is honest, complete,
                rigorous, and novel.

                Domain: {{ domain }} | Topic: {{ topicName }}

                Hypothesis (pre-registered):
                {{ hypothesis }}

                Findings (the prose the author wrote):
                {{ findings }}

                Conclusion:
                {{ conclusion }}

                Tool-call trace (1-indexed; every deterministic computation the agentic
                professor invoked, with arguments and result):
                {{ toolCallTrace }}

                Grade on four axes (each in [0, 1]):
                  - honesty      — do tool results back the prose's specific claims?
                  - completeness — are the relevant tool calls cited in the prose?
                  - rigor        — is the methodology sound (controls, sensitivity, units)?
                  - novelty      — does this materially extend the prior literature?

                For lists of indices, refer to tool-call entries by their 1-based position in
                the trace above. Do not invent contradictions to look thorough — a clean paper
                should yield empty arrays.

                After grading, propose an evidence label from one of:
                  THEOREM | EMPIRICAL_RESULT | CONJECTURE | UNEXPLAINED_OBSERVATION

                Choose conservatively. The label travels through a text-heuristic floor that
                will demote unsupported claims; you cannot earn a higher label than the paper's
                text supports:
                  - THEOREM            — refuse unless either (a) a narrative Proof / Q.E.D. / ∎
                                         structure is present, OR (b) the agentic tool trace
                                         shows a successful prove_identity call (machine-certified
                                         Gosper / Zeilberger-WZ proof with a certificate string
                                         attached). When granting on (b), sanity-check that the
                                         certificate string is syntactically a rational function
                                         in the summation variables — if it isn't, downgrade and
                                         note the discrepancy in `contradictions`.
                  - EMPIRICAL_RESULT   — refuse unless the paper articulates a mechanism AND
                                         performs an adversarial check (e.g. verify_conjecture
                                         on a held-out slice, or analogous out-of-sample test).
                  - CONJECTURE         — default; observation survives but mechanism/adversarial
                                         check is absent.
                  - UNEXPLAINED_OBSERVATION — one-shot result with no mechanism and no
                                              durability story.

                Reply with ONLY this JSON object (no preamble, no markdown):
                {"grades":{"honesty":0.0,"completeness":0.0,"rigor":0.0,"novelty":0.0},\
                 "cited_calls":[],\
                 "omitted_relevant":[],\
                 "contradictions":[{"trace_idx":0,"vs_prose":"..."}],\
                 "recommendations":["..."],\
                 "evidence_label":"CONJECTURE"}
                """));

        // NARRATION_PROFESSOR_SYSTEM — system message for the tool-calling
        // professor agent. Optional {{ auditSection }} slot is populated only
        // when a prior quality audit found gaps; pre-carve used a positional
        // %s for the same conditional.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION,
                """
                You are a professor reviewing a student's computational research paper.
                You have access to mathematical tools to investigate gaps you identify.
                Use tools when they would provide new evidence that materially changes what can be claimed.
                You do not need to use tools if the paper is already well-supported.
                {{ auditSection }}{{ srHint }}
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
                Be direct and specific. If the paper is fundamentally sound, say so briefly.
                If run_sandboxed_code is REJECTED, revise the code to address the rejection reason
                and submit again — you have additional tool rounds available.
                """));

        // NARRATION_PROFESSOR_USER — user message companion to the professor
        // system prompt. Optional pre-registration and result-classification
        // lines fold into the {{ preRegAndClassLines }} composite slot.
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_USER_VERSION,
                """
                Paper for review:
                Domain: {{ domain }} | Topic: {{ topicName }}
                {{ preRegAndClassLines }}
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}
                """));

        // Phase 6 D1.3 — v2 variants of the three professor-path templates with
        // a {{ literatureContext }} slot. Used when
        // app.research.literature-search.enabled is on; the v1 variants remain
        // for the default flag-off path and for Phase 4c Piece 3's eventual A/B.
        // The slot renders to either a literature block or the empty string
        // (no "no literature found" annotation per memo §3.3).
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                "v2-with-literature",
                """
                You are a professor reviewing a student's computational research paper.
                You have access to mathematical tools to investigate gaps you identify.
                Use tools when they would provide new evidence that materially changes what can be claimed.
                You do not need to use tools if the paper is already well-supported.
                {{ literatureContext }}{{ auditSection }}{{ srHint }}
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
                  3. If prior literature was supplied above, note whether the paper's claims align
                     with or extend the cited closed-forms — flag any contradiction.
                Be direct and specific. If the paper is fundamentally sound, say so briefly.
                If run_sandboxed_code is REJECTED, revise the code to address the rejection reason
                and submit again — you have additional tool rounds available.
                """));
        register(new PromptTemplate(
                PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                "v2-with-literature",
                """
                Paper for review:
                Domain: {{ domain }} | Topic: {{ topicName }}
                {{ preRegAndClassLines }}{{ literatureContext }}
                Hypothesis: {{ hypothesis }}
                Findings: {{ findings }}
                Conclusion: {{ conclusion }}
                """));

        // FORMULATION_MAIN — pre-computation hypothesis + parameter selection.
        // Locked once the LLM replies; bots can't revise after results are seen.
        // The three optional context blocks (identitySection, hypothesisSection,
        // noveltyContext) are assembled by the caller and rendered into one
        // composite slot ({{ optionalContext }}); same for priorPapers + the
        // anomalyContext appended to it. Keeps the renderer free of conditional
        // logic per the memo's "no loops, no number formatting" stance.
        register(new PromptTemplate(
                PromptTemplateIds.FORMULATION_MAIN_ID,
                PromptTemplateIds.FORMULATION_MAIN_VERSION,
                """
                You are a research advisor choosing where a computational researcher should focus next.

                Domain: {{ domain }} | Topic: {{ topicName }}
                Researcher's learned search region: param1 center={{ center1 }} (±{{ radius1 }}), param2 center={{ center2 }} (±{{ radius2 }})
                param1 is an integer 10–99 (controls the starting position of the search space).
                param2 is a decimal 1.0–9.0 (controls depth/scale of the search).
                {{ optionalContext }}
                Prior papers in this topic:
                {{ priorPapers }}{{ anomalyContext }}
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
                """));
    }

    /**
     * Phase 4c Piece 3 PR1 — idempotent upsert of every in-memory
     * variant into {@code prompt_template_variants}. Keyed on
     * {@code (templateId, versionTag)}: existing rows have their
     * {@code body} updated (Java-source edits to a template body
     * shouldn't reset its counters), and missing rows are inserted at
     * {@code Status.ACTIVE} with all counters zero.
     *
     * <p>Runs as a separate {@code @PostConstruct} so the Spring
     * lifecycle calls it after {@link #seedDefaults} completes (the
     * registry's two PostConstruct methods both bind to the same bean;
     * their relative ordering is fine because both have no inter-
     * dependency — {@link #syncVariantsToDatabase} reads
     * {@link #variantsById} which {@link #seedDefaults} populated).
     *
     * <p>Skipped silently in unit-test contexts that build the
     * registry via {@code new PromptTemplateRegistry()} — the
     * {@link #variantRepo} field is null then.
     */
    @PostConstruct
    public void syncVariantsToDatabase() {
        if (variantRepo == null) return;
        Instant now = Instant.now();
        int inserted = 0;
        int updated = 0;
        for (List<PromptTemplate> variants : variantsById.values()) {
            for (PromptTemplate t : variants) {
                Optional<PromptTemplateVariant> existing =
                        variantRepo.findByTemplateIdAndVersionTag(t.id(), t.version());
                if (existing.isPresent()) {
                    PromptTemplateVariant row = existing.get();
                    if (!t.body().equals(row.getBody())) {
                        row.setBody(t.body());
                        variantRepo.save(row);
                        updated++;
                    }
                } else {
                    variantRepo.save(new PromptTemplateVariant(
                            t.id(), t.version(), t.body(), now));
                    inserted++;
                }
            }
        }
        log.info("PromptTemplateRegistry: variant sync done inserted={} body-updated={} total-in-memory={}",
                inserted, updated, variantsById.values().stream().mapToInt(List::size).sum());
    }

    /**
     * Appends {@code template} to the variant list for its id. Idempotent
     * on {@code (id, version)} pairs — registering the same version twice
     * collapses to a single entry. Variants for the same id but with
     * different versions accumulate; {@link #pickActive(String)} picks one.
     */
    public void register(PromptTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template is null");
        }
        List<PromptTemplate> variants = variantsById.computeIfAbsent(template.id(), k -> new ArrayList<>());
        // Replace-in-place when the same version is re-registered; otherwise append.
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).version().equals(template.version())) {
                variants.set(i, template);
                return;
            }
        }
        variants.add(template);
    }

    /**
     * Renders {@code id}'s active variant with {@code params}. Throws
     * {@link IllegalArgumentException} when the id is unknown or when the
     * template references a slot not present in {@code params} — that's
     * the typo-catching contract; missing slots are programmer errors,
     * not "render anyway with an empty string".
     */
    public String render(String id, Map<String, Object> params) {
        PromptTemplate template = pickActive(id).orElseThrow(() ->
                new IllegalArgumentException("unknown prompt template id: " + id));
        return renderTemplate(template.body(), params);
    }

    /**
     * Render against an explicitly-pinned {@code version}. Used by tests
     * and audits where the caller wants a specific historical variant
     * regardless of the current "active" selection.
     */
    public String render(String id, String version, Map<String, Object> params) {
        PromptTemplate template = variants(id).stream()
                .filter(v -> v.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown prompt template version: " + id + "/" + version));
        return renderTemplate(template.body(), params);
    }

    /**
     * Current active variant for {@code id}, or empty when no variant is
     * registered.
     *
     * <p>Two selection modes:
     * <ul>
     *   <li><b>Last-registered wins</b> (default, when
     *       {@link #epsilonGreedyEnabled} is false OR variantRepo is
     *       null OR &lt; 2 variants exist for the id). Bit-identical
     *       to the pre-piece-3 behaviour.</li>
     *   <li><b>Epsilon-greedy</b> (when the flag is on AND ≥ 2
     *       in-memory variants AND the DB has matching ACTIVE rows):
     *       with probability {@link #epsilon} pick a uniform-random
     *       ACTIVE arm (exploration); else pick the highest-success-
     *       rate arm. Success rate is
     *       {@code confirmed / max(1, confirmed + failed)}; ties broken
     *       by lower {@code papersNarrated} so newer variants get
     *       additional sample budget. Variants in ROLLED_BACK or
     *       RETIRED status are excluded.</li>
     * </ul>
     *
     * <p>Falls back to last-registered when the DB lookup returns
     * zero ACTIVE rows for the id (e.g. piece-3 PR1's upsert hasn't
     * run yet, or the rows have all been ROLLED_BACK) — never throws.
     */
    public Optional<PromptTemplate> pickActive(String id) {
        List<PromptTemplate> variants = variantsById.get(id);
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        if (epsilonGreedyEnabled && variantRepo != null && variants.size() >= 2) {
            Optional<PromptTemplate> picked = epsilonGreedyPick(id, variants);
            if (picked.isPresent()) return picked;
            // else: fall through to last-registered (no ACTIVE rows in DB yet).
        }
        return Optional.of(variants.get(variants.size() - 1));
    }

    /**
     * Phase 4c Piece 3 PR2 — epsilon-greedy implementation. Reads the
     * persisted ACTIVE variant rows for {@code id} via
     * {@link #variantRepo} and uses their counters to choose between
     * exploration and exploitation.
     *
     * <p>Restricts the in-memory variant list to those with a matching
     * ACTIVE row in the DB; if none match (e.g. all rolled back), returns
     * empty so the caller can fall back. Tie-break in the exploit branch
     * is on lower {@code papersNarrated} so a freshly-added variant gets
     * a chance to accumulate samples before the current best dominates.
     */
    Optional<PromptTemplate> epsilonGreedyPick(String id, List<PromptTemplate> inMemory) {
        List<PromptTemplateVariant> activeRows = variantRepo.findByTemplateIdAndStatus(
                id, PromptTemplateVariant.Status.ACTIVE);
        if (activeRows == null || activeRows.isEmpty()) return Optional.empty();
        // Intersect in-memory variants with ACTIVE rows by version tag.
        Map<String, PromptTemplate> byVersion = new HashMap<>();
        for (PromptTemplate t : inMemory) byVersion.put(t.version(), t);
        List<PromptTemplateVariant> usable = new ArrayList<>();
        for (PromptTemplateVariant row : activeRows) {
            if (byVersion.containsKey(row.getVersionTag())) usable.add(row);
        }
        if (usable.isEmpty()) return Optional.empty();
        if (usable.size() == 1) {
            return Optional.of(byVersion.get(usable.get(0).getVersionTag()));
        }
        // Explore branch.
        if (rng.nextDouble() < epsilon) {
            PromptTemplateVariant arm = usable.get(rng.nextInt(usable.size()));
            return Optional.of(byVersion.get(arm.getVersionTag()));
        }
        // Exploit: highest success rate; tie-break on lower papersNarrated.
        PromptTemplateVariant best = usable.get(0);
        double bestRate = successRate(best);
        for (int i = 1; i < usable.size(); i++) {
            PromptTemplateVariant cand = usable.get(i);
            double candRate = successRate(cand);
            if (candRate > bestRate
                    || (candRate == bestRate && cand.getPapersNarrated() < best.getPapersNarrated())) {
                best = cand;
                bestRate = candRate;
            }
        }
        return Optional.of(byVersion.get(best.getVersionTag()));
    }

    /** Confirmed-replication rate (0.0 when no terminal outcomes yet). */
    private static double successRate(PromptTemplateVariant v) {
        long terminal = v.getReplicationsConfirmed() + v.getReplicationsFailed();
        if (terminal <= 0) return 0.0;
        return (double) v.getReplicationsConfirmed() / (double) terminal;
    }

    /** All registered variants for {@code id}, in registration order. */
    public List<PromptTemplate> variants(String id) {
        List<PromptTemplate> v = variantsById.get(id);
        return v == null ? List.of() : Collections.unmodifiableList(v);
    }

    /**
     * Substitutes {@code {{ name }}} (with optional inner whitespace) in
     * {@code body} with {@code params.get("name").toString()}. Throws
     * {@link IllegalArgumentException} on the first slot not present in
     * {@code params}. The substitution is non-recursive — a replacement
     * value containing another slot is left as-is in the output.
     */
    static String renderTemplate(String body, Map<String, Object> params) {
        if (body == null) return "";
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        Matcher m = SLOT.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!safeParams.containsKey(name)) {
                throw new IllegalArgumentException("missing slot value for: " + name);
            }
            Object value = safeParams.get(name);
            String replacement = value == null ? "" : value.toString();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
