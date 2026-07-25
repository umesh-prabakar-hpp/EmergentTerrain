package com.emergentterrain.research.literature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic validator and fallback planner for both extracted and AI-proposed query intent. */
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
            "stable", "convergent", "converges", "upper", "bound", "bounded", "linear", "combination",
            // Relation, ordinal, and measurement framing terms. They often occur beside a subject
            // word in model prose but do not make a selective literature phrase on their own.
            "has", "have", "had", "one", "order", "over", "consecutive", "high", "location",
            "tracks", "squared",
            // Procedural wording is evidence-method framing, not a research subject. In
            // particular, P-b72c-77627904 let "exact census" through to the title-less
            // full-text fallback, where unrelated astronomy records happened to contain those
            // common words. Keep these terms out of both anchor and pair generation.
            "census", "establish", "established", "establishes", "exact", "itself",
            // Generic model-prose connectors observed to consume the full retrieval deadline
            // before a mathematical subject phrase was tried. They are not literature anchors.
            "integer", "integers", "satisfying", "satisfy", "satisfied", "obey", "obeys",
            "obeying", "mean", "sum", "approximately", "approximate", "requiring", "feature",
            "minus",
            // Comparative and trajectory prose from P76/P77. These describe a claimed state but
            // do not identify a literature subject; letting them form phrases admitted unrelated
            // full-text records that happened to discuss a generic density or baseline.
            "above", "across", "asymptotically", "baseline", "below", "between", "decreases",
            "discriminate", "elevated", "elevation", "exhaustive", "like", "limiting", "local", "known", "relative",
            "remains", "remain", "should", "track", "tracked");

    @Override
    public LiteratureQueryPlan plan(String claim, Map<String, Integer> documentCounts) {
        return plan(claim, documentCounts, null);
    }

    @Override
    public LiteratureQueryPlan plan(String claim, Map<String, Integer> documentCounts,
                                    LiteratureQueryProposal proposal) {
        if (documentCounts == null) {
            return LiteratureQueryPlan.unavailable(
                    "Phase 35 query planning is unavailable; no evidence was issued.");
        }
        if (proposal != null) {
            return planValidatedProposal(claim, proposal);
        }
        List<String> extractedAnchors = extractAnchors(claim);
        List<String> anchors = (documentCounts.isEmpty() ? extractedAnchors.stream()
                : extractedAnchors.stream().filter(documentCounts::containsKey))
                .limit(MAX_RARE_ANCHORS)
                .toList();
        if (anchors.size() < 2) return LiteratureQueryPlan.empty();
        boolean requireTitleAnchor = anchors.size() >= 3;

        List<LiteratureQueryPlan.Candidate> candidates = new ArrayList<>();
        for (List<String> pair : extractAdjacentSubjectPhrases(claim)) {
            if (!anchors.containsAll(pair) || candidates.size() >= MAX_CANDIDATES) continue;
            // An identifier such as omega_Omega_gap tokenizes to omega, omega, gap.
            // A repeated-token pair is neither a two-anchor query nor useful evidence; skip it
            // so planning remains failure-soft and later distinct pairs can still be searched.
            if (pair.get(0).equals(pair.get(1))) continue;
            // A pair of broad measurement words (for example "number density") has no
            // domain-bearing anchor. Keep it from displacing a real subject pair such as
            // "abundant number" or "twin prime".
            if (LiteratureQueryPlan.isWeakSubjectTerm(pair.get(0))
                    && LiteratureQueryPlan.isWeakSubjectTerm(pair.get(1))) continue;
            // Exact subject phrases keep multi-concept claims selective. A two-word claim has no
            // independent third anchor, so its AND pair preserves recall for descriptive titles.
            LiteratureQueryPlan.QueryKind queryKind = requireTitleAnchor
                    ? LiteratureQueryPlan.QueryKind.EXACT_PHRASE
                    : LiteratureQueryPlan.QueryKind.AND_PAIR;
            candidates.add(new LiteratureQueryPlan.Candidate(queryKind, pair,
                    evidenceAnchorsFor(pair, anchors), requireTitleAnchor ? pair : List.of()));
        }
        return new LiteratureQueryPlan(candidates, "");
    }

    /**
     * Models supply semantic intent; this validator owns every retrieval-relevant decision. A
     * proposal must name exactly two safe, distinct domain-bearing tokens already present in the
     * claim. Relation and rationale remain auditable model context, never FTS syntax.
     */
    private static LiteratureQueryPlan planValidatedProposal(String claim, LiteratureQueryProposal proposal) {
        List<String> proposed = proposal.subjectAnchors().stream()
                .map(anchor -> anchor == null ? "" : anchor.strip().toLowerCase(Locale.ROOT))
                .toList();
        if (proposed.size() != 2 || proposed.stream().distinct().count() != 2
                || proposed.stream().anyMatch(anchor -> !TOKEN.matcher(anchor).matches())
                || proposed.stream().anyMatch(anchor -> !isEligibleAnchor(anchor))) {
            return LiteratureQueryPlan.unavailable(
                    "Phase 35 AI literature proposal has no valid two-anchor subject; no evidence was issued.");
        }
        List<String> claimAnchors = extractAnchors(claim);
        if (!claimAnchors.containsAll(proposed)) {
            return LiteratureQueryPlan.unavailable(
                    "Phase 35 AI literature proposal anchors are not grounded in the supplied claim; no evidence was issued.");
        }
        return new LiteratureQueryPlan(List.of(new LiteratureQueryPlan.Candidate(
                LiteratureQueryPlan.QueryKind.AND_PAIR, proposed, proposed, List.of())), "");
    }

    /**
     * Keep the core pair plus one independent, early claim anchor where available. This separates
     * a source's subject/result evidence from later interpretive qualifiers in the same claim.
     */
    private static List<String> evidenceAnchorsFor(List<String> pair, List<String> anchors) {
        Set<String> evidence = new LinkedHashSet<>(pair);
        for (String anchor : anchors) {
            if (evidence.size() >= 3) break;
            evidence.add(anchor);
        }
        return List.copyOf(evidence);
    }

    static List<String> extractAnchors(String input) {
        Set<String> anchors = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(input == null ? "" : input);
        while (matcher.find() && anchors.size() < MAX_INPUT_TERMS) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (isEligibleAnchor(term)) anchors.add(term);
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
            if (!isEligibleAnchor(first) || !isEligibleAnchor(second)) continue;
            String fingerprint = first + " " + second;
            if (seen.add(fingerprint)) phrases.add(List.of(first, second));
        }
        return List.copyOf(phrases);
    }

    /**
     * Query anchors must be lexical subject terms. Coefficient fragments and model relation prose
     * produce broad FTS phrases (for example {@code density 0000} or {@code requiring density})
     * without adding subject evidence, so they are deliberately unavailable to both anchor and pair
     * generation.
     */
    private static boolean isEligibleAnchor(String term) {
        if (NON_ANCHOR_TERMS.contains(term)) return false;
        return term.codePoints().allMatch(Character::isLetter);
    }
}
