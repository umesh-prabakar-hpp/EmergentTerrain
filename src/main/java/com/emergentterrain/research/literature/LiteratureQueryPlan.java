package com.emergentterrain.research.literature;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** A bounded set of safe FTS candidates and the record-evidence rules each must satisfy. */
record LiteratureQueryPlan(List<Candidate> candidates, String unavailableReason) {

    /**
     * Broad measurement words are useful only beside a subject term. They cannot by themselves
     * establish that a returned full-text record is about the claim's research subject.
     */
    private static final Set<String> WEAK_SUBJECT_TERMS = Set.of(
            "density", "fraction", "fractions", "number", "numbers", "range", "interval",
            "value", "values", "result", "results", "scale", "scales", "deviation", "deviations");

    LiteratureQueryPlan {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }

    static LiteratureQueryPlan unavailable(String reason) {
        return new LiteratureQueryPlan(List.of(), reason);
    }

    static LiteratureQueryPlan empty() {
        return new LiteratureQueryPlan(List.of(), "");
    }

    static boolean isWeakSubjectTerm(String term) {
        return WEAK_SUBJECT_TERMS.contains(term);
    }

    /**
     * A query pair plus a deterministic rule for deciding whether an FTS hit is citation-worthy.
     *
     * <p>An eventual AI planner may choose a better pair, but cannot ask the adapter to issue a
     * key without satisfying these bounded, token-only checks.
     */
    record Candidate(QueryKind queryKind, List<String> queryAnchors, List<String> evidenceAnchors,
                     List<String> requiredTitleAnchors) {
        private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]{2,}");

        Candidate {
            queryAnchors = queryAnchors == null ? List.of() : queryAnchors.stream()
                    .map(anchor -> anchor == null ? "" : anchor.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            evidenceAnchors = evidenceAnchors == null ? List.of() : evidenceAnchors.stream()
                    .map(anchor -> anchor == null ? "" : anchor.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            requiredTitleAnchors = requiredTitleAnchors == null ? List.of() : requiredTitleAnchors.stream()
                    .map(anchor -> anchor == null ? "" : anchor.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (evidenceAnchors.stream().anyMatch(anchor -> !TOKEN.matcher(anchor).matches())) {
                throw new IllegalArgumentException("literature evidence anchors must be safe tokens");
            }
            if (requiredTitleAnchors.stream().anyMatch(anchor -> !TOKEN.matcher(anchor).matches())) {
                throw new IllegalArgumentException("literature title anchors must be safe tokens");
            }
            if (queryKind == null || queryAnchors.size() != 2 || queryAnchors.stream()
                    .anyMatch(anchor -> !TOKEN.matcher(anchor).matches())
                    || !evidenceAnchors.containsAll(queryAnchors)
                    || !queryAnchors.containsAll(requiredTitleAnchors)) {
                throw new IllegalArgumentException("literature candidate must have validated bounded anchors");
            }
        }

        int minimumEvidenceMatches() {
            return evidenceAnchors.size();
        }

        String expression() {
            return queryKind == QueryKind.EXACT_PHRASE
                    ? "\"" + String.join(" ", queryAnchors) + "\""
                    : String.join(" AND ", queryAnchors);
        }
    }

    enum QueryKind {
        EXACT_PHRASE,
        AND_PAIR
    }
}
