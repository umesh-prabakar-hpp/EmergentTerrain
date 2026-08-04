package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-05-21 review §1 rec #5 — flags textual author-year citations in
 * paper prose that don't match anything in {@code literature-corpus.yml}.
 * Targets the fabrication cluster: P-a328-862 cited "P-2022-07" (Unicode-
 * dashed paper-ID that the room dedup gate didn't catch); P-a328-872 cited
 * "Torquato and Stillinger"; P-a328-890 cited "Hecht Optics 5th ed."
 *
 * <p>Scope (v1):
 * <ul>
 *   <li>Extract author-year tokens: "Lastname (1923)", "Lastname 1923",
 *       "Lastname & Other (1923)", "Lastname-Other 1923".</li>
 *   <li>For each, check the corpus for at least one
 *       {@link LiteratureReference#citation()} string containing both the
 *       lastname token AND the year.</li>
 *   <li>Flag any token without a corpus match.</li>
 * </ul>
 *
 * <p>What this does NOT catch (deferred):
 * <ul>
 *   <li>Textbook references without years (e.g. "Hecht Optics 5th ed.",
 *       "Torquato and Stillinger"). v2 could extend with a known-textbook
 *       allowlist.</li>
 *   <li>Numeric paper-IDs (e.g. P-2022-07) — covered by the existing
 *       in-room citation match in
 *       {@link UniversityService}. v2 could also Unicode-normalise
 *       dashes (‑ U+2010, ‐ U+2011) before that match runs.</li>
 *   <li>OEIS citations, arXiv IDs, DOI strings — distinct shapes, distinct
 *       allowlists.</li>
 * </ul>
 *
 * <p>Stateless and pure; corpus passed by argument so the validator can be
 * unit-tested without the Spring context.
 */
public final class CitationCorpusValidator {

    /**
     * Matches "Lastname (Year)" / "Lastname Year" / "Lastname & Other (Year)"
     * / "Lastname-Other Year". Year is a 4-digit token in [1800, 2099] to
     * skip stray numbers and equation references. Lastname starts with an
     * uppercase letter to skip leading common nouns; trailing surname after
     * "&" / "-" / "and" is optional.
     */
    private static final Pattern AUTHOR_YEAR = Pattern.compile(
            "\\b([A-Z][a-z]{2,}(?:[-\\s]+(?:&|and|et al\\.?)?\\s*[A-Z][a-z]{2,})?)\\s*"
                    + "(?:\\([^)]*?(1[89]\\d{2}|20\\d{2})\\)|"
                    + "(1[89]\\d{2}|20\\d{2}))");

    /**
     * Allowlist of single-token year-free names that pass without a year.
     * Empty in v1; extension point for textbook-only citations.
     */
    private static final Set<String> KNOWN_TEXTBOOK_TOKENS = Set.of();

    private CitationCorpusValidator() {}

    /**
     * Returns a list of "{name} (year)" citations that lack a corpus
     * match. Empty when the prose has no extractable author-year tokens,
     * or every token matches at least one corpus citation.
     */
    public static List<String> validate(String paperProse,
                                         Collection<LiteratureReference> corpus) {
        return validate(paperProse, corpus, null);
    }

    /**
     * 2026-07-04 (P-b72c-77627765) — trace-aware overload. With §32.5 grounding live, the
     * harness ITSELF hands the narrator author-year references (the grounded search_literature
     * result carries "Jean–Marie De Koninck (1974) Sums of Quotients…"); citing them is exactly
     * the instructed behavior, but this validator only knew the curated
     * {@code literature-corpus.yml} and flagged "Koninck (1974)" as corpus-absent (warn-mode).
     * A cite whose name+year appear in any tool-call result is tool-provided, not fabricated —
     * exempt. {@code toolResultsText} is the concatenated result texts (null = old behavior).
     */
    public static List<String> validate(String paperProse,
                                         Collection<LiteratureReference> corpus,
                                         String toolResultsText) {
        if (paperProse == null || paperProse.isBlank()
                || corpus == null || corpus.isEmpty()) {
            return List.of();
        }

        List<String> failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = AUTHOR_YEAR.matcher(paperProse);
        while (m.find()) {
            String name = m.group(1).trim();
            String year = m.group(2) != null ? m.group(2) : m.group(3);
            if (year == null) continue;
            // Skip our own paper-ID format (P-XXXX-NN) — covered elsewhere.
            // Skip year-only matches that don't actually anchor an author.
            String key = name + "|" + year;
            if (!seen.add(key)) continue;
            if (KNOWN_TEXTBOOK_TOKENS.contains(name)) continue;
            if (matchesCorpus(name, year, corpus)) continue;
            if (matchesText(name, year, toolResultsText)) continue;
            failures.add(name + " (" + year + ")");
        }
        return failures;
    }

    /** True when the cite's first name token AND year both appear in the tool-results text. */
    private static boolean matchesText(String name, String year, String text) {
        if (text == null || text.isBlank()) return false;
        String firstToken = name.split("[-\\s&]")[0];
        return text.contains(year) && text.contains(firstToken);
    }

    private static boolean matchesCorpus(String name, String year,
                                          Collection<LiteratureReference> corpus) {
        String firstToken = name.split("[-\\s&]")[0];
        for (LiteratureReference ref : corpus) {
            String cite = ref.citation();
            if (cite == null) continue;
            if (cite.contains(year) && cite.contains(firstToken)) return true;
        }
        return false;
    }
}
