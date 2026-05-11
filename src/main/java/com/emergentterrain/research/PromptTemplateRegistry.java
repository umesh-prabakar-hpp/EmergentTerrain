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
        // round-trip, JSON-shaped reply. Migrated in Piece 2's first wave; the
        // remaining 10 PromptTemplateIds entries are follow-up migrations.
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
