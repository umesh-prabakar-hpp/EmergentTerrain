package com.emergentterrain.research.literature;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A bounded set of safe FTS candidates and the record-evidence rules each must satisfy. */
record LiteratureQueryPlan(List<Candidate> candidates, String unavailableReason) {

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

    /**
     * A query pair plus a deterministic rule for deciding whether an FTS hit is citation-worthy.
     *
     * <p>An eventual AI planner may choose a better pair, but cannot ask the adapter to issue a
     * key without satisfying these bounded, token-only checks.
     */
    record Candidate(QueryKind queryKind, List<String> queryAnchors, List<String> evidenceAnchors,
                     int minimumEvidenceMatches, List<String> requiredTitleAnchors) {
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
                    || !queryAnchors.containsAll(requiredTitleAnchors)
                    || minimumEvidenceMatches < queryAnchors.size()
                    || minimumEvidenceMatches > evidenceAnchors.size()) {
                throw new IllegalArgumentException("literature candidate must have validated bounded anchors");
            }
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
