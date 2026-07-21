package com.emergentterrain.research.literature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic phrase planner used until a reviewed AI planner is introduced. */
final class DeterministicLiteratureQueryPlanner implements LiteratureQueryPlanner {

    private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]{2,}");
    private static final int MAX_INPUT_TERMS = 32;
    private static final int MAX_RARE_ANCHORS = 8;
    private static final int MAX_CANDIDATES = 12;
    private static final Set<String> NON_ANCHOR_TERMS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "claim", "claims",
            "compare", "could", "do", "does", "evidence", "find", "for", "from", "how", "in",
            "is", "it", "its", "literature", "look", "lookup", "of", "on", "or", "paper",
            "papers", "please", "prior", "provide", "read", "reference", "references", "related",
            "not", "research", "result", "results", "search", "searching", "show", "source", "sources",
            "study", "studies", "that", "the", "these", "this", "to", "use", "using", "was",
            "were", "what", "with", "would",
            "alternative", "asymptotic", "bounds", "caused", "constant", "constraints", "coordinate",
            "corrections", "count", "decrease", "decreasing", "divided", "exp", "finite", "follows",
            "function", "geometric", "growth", "ladder", "law", "log", "maximal", "may", "million",
            "near", "normalized", "observed", "plausible", "scale", "slowly", "such", "tending", "tight",
            "than", "then", "times", "toward", "trajectory", "type", "up", "when", "while", "window", "windows",
            "two", "term", "vs", "sq", "statistic", "empirical", "inequality", "admits", "rather",
            "stable", "convergent", "converges", "upper", "bound", "bounded", "linear", "combination");

    @Override
    public LiteratureQueryPlan plan(String claim, Map<String, Integer> documentCounts) {
        if (documentCounts == null) {
            return LiteratureQueryPlan.unavailable(
                    "Phase 35 query planning is unavailable; no evidence was issued.");
        }
        List<String> extractedAnchors = extractAnchors(claim);
        List<String> anchors = (documentCounts.isEmpty() ? extractedAnchors.stream()
                : extractedAnchors.stream().filter(documentCounts::containsKey))
                .limit(MAX_RARE_ANCHORS)
                .toList();
        if (anchors.size() < 2) return LiteratureQueryPlan.empty();
        int minimumEvidenceMatches = Math.min(3, anchors.size());
        boolean requireTitleAnchor = anchors.size() >= 3;

        List<LiteratureQueryPlan.Candidate> candidates = new ArrayList<>();
        for (List<String> pair : extractAdjacentSubjectPhrases(claim)) {
            if (!anchors.containsAll(pair) || candidates.size() >= MAX_CANDIDATES) continue;
            // An identifier such as omega_Omega_gap tokenizes to omega, omega, gap.
            // A repeated-token pair is neither a two-anchor query nor useful evidence; skip it
            // so planning remains failure-soft and later distinct pairs can still be searched.
            if (pair.get(0).equals(pair.get(1))) continue;
            // Exact subject phrases keep multi-concept claims selective. A two-word claim has no
            // independent third anchor, so its AND pair preserves recall for descriptive titles.
            LiteratureQueryPlan.QueryKind queryKind = requireTitleAnchor
                    ? LiteratureQueryPlan.QueryKind.EXACT_PHRASE
                    : LiteratureQueryPlan.QueryKind.AND_PAIR;
            candidates.add(new LiteratureQueryPlan.Candidate(queryKind, pair, anchors,
                    minimumEvidenceMatches, requireTitleAnchor ? pair : List.of()));
        }
        return new LiteratureQueryPlan(candidates, "");
    }

    static List<String> extractAnchors(String input) {
        Set<String> anchors = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(input == null ? "" : input);
        while (matcher.find() && anchors.size() < MAX_INPUT_TERMS) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (!NON_ANCHOR_TERMS.contains(term)) anchors.add(term);
        }
        return List.copyOf(anchors);
    }

    private static List<List<String>> extractAdjacentSubjectPhrases(String input) {
        List<String> rawTerms = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input == null ? "" : input);
        while (matcher.find() && rawTerms.size() < MAX_INPUT_TERMS) {
            rawTerms.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        List<List<String>> phrases = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index + 1 < rawTerms.size(); index++) {
            String first = rawTerms.get(index);
            String second = rawTerms.get(index + 1);
            if (NON_ANCHOR_TERMS.contains(first) || NON_ANCHOR_TERMS.contains(second)) continue;
            String fingerprint = first + " " + second;
            if (seen.add(fingerprint)) phrases.add(List.of(first, second));
        }
        return List.copyOf(phrases);
    }
}
