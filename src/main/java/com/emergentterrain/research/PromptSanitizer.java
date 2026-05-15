package com.emergentterrain.research;

import java.util.regex.Pattern;

/**
 * Phase 5 A4 — strips harness-parameter leakage out of user-visible LLM prose.
 *
 * <p>The May 2026 corpus contained ≥ 11 papers whose Hypothesis text leaked
 * simulator internals into what should be a scientific premise:
 * <ul>
 *   <li>"…starting at position 10 and uses a scale of 7.0…"
 *       (P-a328-1, 5, 6, 7, 8, 13, 17, 20, 28, 31, 41)</li>
 *   <li>"…the actual quality score of 0.23 remained unaffected by changes
 *       in sampling parameters." (P-a328-28)</li>
 *   <li>"…when the search starts at position 31" (P-a328-326)</li>
 *   <li>"…applied from a starting cycle index of 41 with a scale factor of 6.2…"
 *       (P-a328-329, em-dash-delimited parenthetical)</li>
 *   <li>"…that start near position 270 and are examined with a scaling factor
 *       of 5.5…" (P-a328-341)</li>
 * </ul>
 *
 * <p>These phrases come from the narrator LLM reading the {@link PaperDraft}
 * params (param1, param2) and the peer-score telemetry, then describing them
 * as if they were physical positions or scientific results. Internal numeric
 * IDs continue to flow into the simulator as opaque seeds via the existing
 * {@code param1}/{@code param2} plumbing; this class only scrubs the
 * surfaced prose.
 *
 * <p>The sanitizer is conservative: it only touches a small allowlist of
 * regex patterns the report cites verbatim. Each pattern is anchored to
 * its connector ({@code ,}, {@code and}) so the surrounding sentence
 * grammar stays intact after removal.
 */
public final class PromptSanitizer {

    private PromptSanitizer() {}

    /**
     * Optional connector ({@code ,} and/or {@code and}) plus whitespace.
     * Captured so we can re-introduce a single space when the connector is
     * stripped together with the offending phrase.
     */
    private static final String CONNECTOR_PREFIX = "(?:\\s*,)?\\s*(?:and\\s+)?";

    private static final Pattern STARTING_AT_POSITION =
            Pattern.compile(CONNECTOR_PREFIX + "starting at position \\d+", Pattern.CASE_INSENSITIVE);

    /**
     * Extended from {@code uses? a scale of [\d.]+} to also match "uses a scaling
     * factor of N" / "uses a scale factor of N" — the P-a328-341 / P-a328-329
     * variants from the research/a3289005 refresh. Optional "(factor)" head-noun.
     */
    private static final Pattern USES_A_SCALE_OF =
            Pattern.compile(CONNECTOR_PREFIX + "uses? a scal(?:e|ing)(?:\\s+factor)? of [\\d.]+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "the actual quality score of 0.23 remained unaffected by …" — the P-a328-28
     * leak. Matches the full sentence so we don't leave a dangling subject.
     */
    private static final Pattern QUALITY_SCORE_SENTENCE =
            Pattern.compile("\\s*(?:the actual )?quality score of [\\d.]+[^.]*\\.",
                    Pattern.CASE_INSENSITIVE);

    // Phase 7 G4 — additional patterns from REVIEW_REPORT.md refresh #2-#12.

    /**
     * "as observed from the starting position of 10 and scale of 7.7" — the P-237
     * leak (rec #14). Same root cause as STARTING_AT_POSITION but a different
     * phrasing the prior regex didn't catch.
     */
    private static final Pattern OBSERVED_FROM_POSITION_AND_SCALE =
            Pattern.compile(CONNECTOR_PREFIX
                    + "(?:as observed from )?the starting position of \\d+ and scale of [\\d.]+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "with a scale of 1.0" — the P-256 leak (rec #14). Extended to also match
     * "with a scaling factor of N" / "with a scale factor of N" — the P-a328-341
     * / P-a328-329 variants from the research/a3289005 refresh. Optional
     * "(factor)" head-noun and an optional "(are\s+examined\s+)?" preamble that
     * P-a328-341's phrasing requires.
     */
    private static final Pattern WITH_A_SCALE_OF =
            Pattern.compile(CONNECTOR_PREFIX
                    + "(?:are\\s+examined\\s+)?with a scal(?:e|ing)(?:\\s+factor)? of [\\d.]+",
                    Pattern.CASE_INSENSITIVE);

    /** "starting from parametric index 56" — the P-250 / P-288 leak (rec #14). */
    private static final Pattern STARTING_FROM_PARAMETRIC_INDEX =
            Pattern.compile(CONNECTOR_PREFIX + "starting from parametric index \\d+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Dummy citation placeholder: "(see P-xxxx-1)" — the P-300 leak (rec #33). The
     * narrator emitted the prompt's literal placeholder string instead of a real
     * citation. Strip the parenthetical entirely; a real citation would have come
     * through the typed-citations side channel.
     */
    private static final Pattern DUMMY_CITATION_PLACEHOLDER =
            Pattern.compile("\\s*\\(see P-x{2,4}-\\d+\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Numerical formatting bug: "3.89×10^1" — the P-274 / P-287 leak (rec #26). The
     * LLM emitted scientific notation where a plain decimal was intended, so
     * "3.89×10^1" reads as 38.9 when it should be 3.89. Strip the spurious
     * "×10^1" suffix to restore the intended value (capture group 1 is the
     * decimal; the replacement keeps just that).
     */
    private static final Pattern SCIENTIFIC_NOTATION_FORMATTING_BUG =
            Pattern.compile("(\\d+\\.\\d+)\\s*[×x]\\s*10\\^1\\b");

    // Phase 9 G7 — additional patterns from REVIEW_REPORT.md (research/a3289005).

    /**
     * Em-dash-delimited parenthetical clause containing a harness leak.
     * E.g., P-a328-329's "σ_a = σ'_f·(2·N_f)^b—applied from a starting cycle
     * index of 41 with a scale factor of 6.2—will generate…" — the entire
     * parenthetical is dropped (replaced with a single space) because the
     * granular patterns alone would leave dangling "—applied from a —" debris.
     * Run before the granular patterns.
     */
    private static final Pattern EMDASH_HARNESS_CLAUSE =
            Pattern.compile("\\s*—[^—]*?(?:starting\\s+(?:cycle\\s+)?index\\s+of|"
                    + "starts?\\s+(?:at|near)\\s+position|"
                    + "scal(?:e|ing)\\s+factor\\s+of)[^—]*—\\s*",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "when the search starts at position 31" — the P-a328-326 leak. The
     * harness scaffolding bleeds into a sub-clause connected by "when", which
     * the CONNECTOR_PREFIX-anchored patterns don't reach. Matches "at" or
     * "near" as the preposition.
     */
    private static final Pattern WHEN_SEARCH_STARTS_AT_POSITION =
            Pattern.compile("\\s*when the search starts? (?:at|near) position \\d+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "(that) start(s) near position N" — the P-a328-341 leak. Sibling of
     * {@link #STARTING_AT_POSITION} with "near" instead of "at" and an optional
     * "(that)" relative-clause prefix that P-a328-341's phrasing requires.
     */
    private static final Pattern STARTS_NEAR_POSITION =
            Pattern.compile(CONNECTOR_PREFIX + "(?:that\\s+)?starts?\\s+near\\s+position\\s+\\d+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "starting cycle index of N" / "starting index of N" — the P-a328-329
     * leak. Sibling of {@link #STARTING_FROM_PARAMETRIC_INDEX} with a
     * different lead-in noun. Optional "(applied\s+from\s+a\s+)?" preamble
     * accommodates the P-a328-329 phrasing when the em-dash strip fails to
     * fire (e.g. only one em-dash present).
     */
    private static final Pattern STARTING_INDEX_OF =
            Pattern.compile(CONNECTOR_PREFIX
                    + "(?:applied\\s+from\\s+a\\s+)?starting\\s+(?:cycle\\s+)?index\\s+of\\s+\\d+",
                    Pattern.CASE_INSENSITIVE);

    /** Collapses any whitespace/punctuation debris left behind by removals. */
    private static final Pattern CLEANUP_DOUBLE_COMMA   = Pattern.compile("\\s*,\\s*,");
    private static final Pattern CLEANUP_DOUBLE_PERIOD  = Pattern.compile("\\.\\s*\\.");
    private static final Pattern CLEANUP_COMMA_PERIOD   = Pattern.compile(",\\s*\\.");
    private static final Pattern CLEANUP_LEADING_AND    = Pattern.compile("(?<=[.!?]\\s)and\\s+");
    private static final Pattern CLEANUP_DOUBLE_SPACE   = Pattern.compile("\\s{2,}");
    private static final Pattern CLEANUP_SPACE_PUNCT    = Pattern.compile("\\s+([.,;!?])");

    /**
     * Returns {@code text} with harness-parameter leak phrases removed and surrounding
     * grammar cleaned up. Returns {@code null} unchanged so the upstream template
     * fallback in {@link UniversityService#publishPaper} still fires.
     */
    public static String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        String cleaned = text;
        // Phase 9 G7 wholesale clause strip runs FIRST so the em-dash-delimited
        // parentheticals don't leave dangling "—applied from a —" debris after
        // the granular patterns fire.
        cleaned = EMDASH_HARNESS_CLAUSE.matcher(cleaned).replaceAll(" ");
        // Phase 5 A4 patterns
        cleaned = STARTING_AT_POSITION.matcher(cleaned).replaceAll("");
        cleaned = USES_A_SCALE_OF.matcher(cleaned).replaceAll("");
        cleaned = QUALITY_SCORE_SENTENCE.matcher(cleaned).replaceAll("");
        // Phase 7 G4 patterns
        cleaned = OBSERVED_FROM_POSITION_AND_SCALE.matcher(cleaned).replaceAll("");
        cleaned = WITH_A_SCALE_OF.matcher(cleaned).replaceAll("");
        cleaned = STARTING_FROM_PARAMETRIC_INDEX.matcher(cleaned).replaceAll("");
        cleaned = DUMMY_CITATION_PLACEHOLDER.matcher(cleaned).replaceAll("");
        cleaned = SCIENTIFIC_NOTATION_FORMATTING_BUG.matcher(cleaned).replaceAll("$1");
        // Phase 9 G7 granular patterns
        cleaned = WHEN_SEARCH_STARTS_AT_POSITION.matcher(cleaned).replaceAll("");
        cleaned = STARTS_NEAR_POSITION.matcher(cleaned).replaceAll("");
        cleaned = STARTING_INDEX_OF.matcher(cleaned).replaceAll("");
        // Tidy up whatever stripping left behind.
        cleaned = CLEANUP_DOUBLE_COMMA.matcher(cleaned).replaceAll(",");
        cleaned = CLEANUP_DOUBLE_PERIOD.matcher(cleaned).replaceAll(".");
        cleaned = CLEANUP_COMMA_PERIOD.matcher(cleaned).replaceAll(".");
        cleaned = CLEANUP_LEADING_AND.matcher(cleaned).replaceAll("");
        cleaned = CLEANUP_SPACE_PUNCT.matcher(cleaned).replaceAll("$1");
        cleaned = CLEANUP_DOUBLE_SPACE.matcher(cleaned).replaceAll(" ");
        return cleaned.trim();
    }

    // Phase 7 G5 — boilerplate sentences emitted by the MathPhysicsEngine fallback
    // path when no peer review actually ran. Per REVIEW_REPORT.md refresh #1-#12,
    // ~75% of the corpus had critique:skip in the call path yet carried these
    // three sentences claiming peer review. Suppressing this restores the
    // rubric's "no-boilerplate" requirement on conclusions.
    private static final Pattern BOILERPLATE_INDEPENDENTLY_REPRODUCED =
            Pattern.compile(
                    "\\s*Computed values were independently reproduced by peer reviewers\\.",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BOILERPLATE_OBSERVATIONAL_BASELINE =
            Pattern.compile(
                    "\\s*These findings represent an observational baseline\\.",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BOILERPLATE_FINITE_AND_REPRODUCED =
            Pattern.compile(
                    "\\s*The computed values are finite and reproduced under the search params declared above\\.",
                    Pattern.CASE_INSENSITIVE);

    private static final String CRITIQUE_SKIP_DISCLAIMER =
            " [critique:skip — no peer review performed; this paper has not been validated].";

    /**
     * Phase 7 G5 — suppress the "independently reproduced by peer reviewers" boilerplate
     * sentence when the call path indicates peer review actually was skipped. Returns
     * {@code conclusion} unchanged if peer-review-skipped wasn't indicated; otherwise
     * removes the boilerplate trio and appends an explicit disclaimer so readers know
     * the paper was not validated.
     *
     * @param conclusion the conclusion prose, possibly already sanitised by {@link #sanitize}
     * @param callPath   the call path string from {@code NarrationResult.callPath()}
     * @return the conclusion with boilerplate removed iff peer-review was skipped
     */
    public static String suppressBoilerplateOnSkip(String conclusion, String callPath) {
        if (conclusion == null || conclusion.isBlank()) return conclusion;
        if (callPath == null || !callPath.contains("critique:skip")) return conclusion;
        String cleaned = conclusion;
        cleaned = BOILERPLATE_INDEPENDENTLY_REPRODUCED.matcher(cleaned).replaceAll("");
        cleaned = BOILERPLATE_OBSERVATIONAL_BASELINE.matcher(cleaned).replaceAll("");
        cleaned = BOILERPLATE_FINITE_AND_REPRODUCED.matcher(cleaned).replaceAll("");
        cleaned = CLEANUP_DOUBLE_SPACE.matcher(cleaned).replaceAll(" ").trim();
        // Append disclaimer once (don't double-stamp if the method is called twice).
        if (!cleaned.contains("[critique:skip")) {
            cleaned = cleaned.isEmpty()
                    ? CRITIQUE_SKIP_DISCLAIMER.trim()
                    : cleaned + CRITIQUE_SKIP_DISCLAIMER;
        }
        return cleaned;
    }

    /** True iff {@link #sanitize} would change the input — useful for telemetry. */
    public static boolean detects(String text) {
        if (text == null || text.isBlank()) return false;
        return STARTING_AT_POSITION.matcher(text).find()
                || USES_A_SCALE_OF.matcher(text).find()
                || QUALITY_SCORE_SENTENCE.matcher(text).find()
                || OBSERVED_FROM_POSITION_AND_SCALE.matcher(text).find()
                || WITH_A_SCALE_OF.matcher(text).find()
                || STARTING_FROM_PARAMETRIC_INDEX.matcher(text).find()
                || DUMMY_CITATION_PLACEHOLDER.matcher(text).find()
                || SCIENTIFIC_NOTATION_FORMATTING_BUG.matcher(text).find()
                || EMDASH_HARNESS_CLAUSE.matcher(text).find()
                || WHEN_SEARCH_STARTS_AT_POSITION.matcher(text).find()
                || STARTS_NEAR_POSITION.matcher(text).find()
                || STARTING_INDEX_OF.matcher(text).find();
    }
}
