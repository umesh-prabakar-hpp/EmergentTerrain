package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;

/**
 * Game-owned negative validation policy for the research-result projection boundary.
 *
 * <p>The current contract admits planning records only. Any future effect-bearing result needs a
 * separately versioned contract and an explicit game validation policy; it cannot pass through
 * this display projection by accident.</p>
 */
final class ResearchResultProjectionPolicy {
    private static final Set<String> DISPLAY_ONLY_OUTCOMES = Set.of(
            "DRAFT_CANDIDATE_PLANNED", "DRAFT_BRANCH_PREPARED");
    private static final Set<String> FORBIDDEN_FINDING_FIELDS = Set.of(
            "effect", "effects", "effectrecommendation", "effectrecommendations",
            "gameplayeffect", "gameplayeffects", "gameplayaction", "gameplayactions",
            "gamemutation", "gamemutations", "gamecommand", "gamecommands");

    private ResearchResultProjectionPolicy() { }

    static void validate(ResearchResult result) {
        if (!DISPLAY_ONLY_OUTCOMES.contains(result.outcome())) {
            throw new IllegalArgumentException("Research result outcome is not approved for display-only projection");
        }
        if (result.summary().length() > 8_192) {
            throw new IllegalArgumentException("Research result summary exceeds the display-only projection limit");
        }
        if (!result.findings().isObject()) {
            throw new IllegalArgumentException("Research result findings must be an object for display-only projection");
        }
        rejectEffectFields(result.findings());
    }

    private static void rejectEffectFields(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_FINDING_FIELDS.contains(normalizeFieldName(entry.getKey()))) {
                    throw new IllegalArgumentException("Research results may not carry gameplay effects or recommendations");
                }
                rejectEffectFields(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(ResearchResultProjectionPolicy::rejectEffectFields);
        }
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
