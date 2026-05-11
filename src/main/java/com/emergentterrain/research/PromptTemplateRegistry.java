package com.emergentterrain.research;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    private final Map<String, List<PromptTemplate>> variantsById = new HashMap<>();

    @PostConstruct
    void seedDefaults() {
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

    /** Current active variant for {@code id}, or empty when no variant is registered. */
    public Optional<PromptTemplate> pickActive(String id) {
        List<PromptTemplate> variants = variantsById.get(id);
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        // Piece 2: last-registered wins. Piece 3 swaps this for an
        // epsilon-greedy selector that takes the per-domain stats into account.
        return Optional.of(variants.get(variants.size() - 1));
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
