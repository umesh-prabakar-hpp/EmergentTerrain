package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-05-21 review §1 rec #6 — flags published prose that mentions a
 * topic-registry name OTHER than the paper's declared topic. Targets the
 * template-bleed cluster (P-a328-886's Abundant Density paper said "the
 * specific focus on the Pendulum Breakdown Angle is new" — the narrator's
 * context evidently held another paper's topic placeholder).
 *
 * <p>Scope (v1):
 * <ul>
 *   <li>Whole-phrase, case-insensitive match against the registered topic
 *       names. "Pendulum Breakdown Angle" matches; "pendulum" alone does
 *       not — that would over-flag legitimate physics discussion.</li>
 *   <li>The paper's declared topic is excluded; finding your own topic
 *       name in prose is expected (the title and introduction usually
 *       repeat it).</li>
 *   <li>Duplicate matches are deduplicated per paper.</li>
 * </ul>
 *
 * <p>What this does NOT catch (deferred):
 * <ul>
 *   <li>Domain-name leaks (e.g. PHYSICS prose appearing in a MATHEMATICS
 *       paper). The domain-drift gate already addresses this separately.</li>
 *   <li>Single-word topic fragments — too noisy for v1.</li>
 *   <li>Topic synonyms / abbreviations not in the registry.</li>
 * </ul>
 *
 * <p>Stateless and pure; topic list passed by argument so the validator
 * can be unit-tested without the Spring context.
 */
public final class TopicConsistencyValidator {

    private TopicConsistencyValidator() {}

    /**
     * Returns the list of foreign topic names that appear (whole-phrase,
     * case-insensitive) in the prose. Empty when the paper's declared
     * topic is the only match (or no topic names appear). Empty on any
     * null / blank input so callers can no-op the gate when the
     * registry is uninitialised.
     */
    public static List<String> validate(String declaredTopic, String prose,
                                         Collection<String> allTopics) {
        if (declaredTopic == null || prose == null || prose.isBlank()
                || allTopics == null || allTopics.isEmpty()) {
            return List.of();
        }

        String ownNorm = declaredTopic.trim().toLowerCase();
        Set<String> hits = new LinkedHashSet<>();
        for (String topic : allTopics) {
            if (topic == null || topic.isBlank()) continue;
            if (topic.trim().toLowerCase().equals(ownNorm)) continue;
            // Whole-phrase, case-insensitive, word-boundary anchored.
            Pattern p = Pattern.compile(
                    "(?i)\\b" + Pattern.quote(topic) + "\\b");
            Matcher m = p.matcher(prose);
            if (m.find()) hits.add(topic);
        }
        return new ArrayList<>(hits);
    }
}
