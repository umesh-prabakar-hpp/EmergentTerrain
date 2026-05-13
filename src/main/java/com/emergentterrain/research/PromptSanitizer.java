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

    private static final Pattern USES_A_SCALE_OF =
            Pattern.compile(CONNECTOR_PREFIX + "uses? a scale of [\\d.]+", Pattern.CASE_INSENSITIVE);

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

    /** "with a scale of 1.0" — the P-256 leak (rec #14). */
    private static final Pattern WITH_A_SCALE_OF =
            Pattern.compile(CONNECTOR_PREFIX + "with a scale of [\\d.]+", Pattern.CASE_INSENSITIVE);

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
        // Tidy up whatever stripping left behind.
        cleaned = CLEANUP_DOUBLE_COMMA.matcher(cleaned).replaceAll(",");
        cleaned = CLEANUP_DOUBLE_PERIOD.matcher(cleaned).replaceAll(".");
        cleaned = CLEANUP_COMMA_PERIOD.matcher(cleaned).replaceAll(".");
        cleaned = CLEANUP_LEADING_AND.matcher(cleaned).replaceAll("");
        cleaned = CLEANUP_SPACE_PUNCT.matcher(cleaned).replaceAll("$1");
        cleaned = CLEANUP_DOUBLE_SPACE.matcher(cleaned).replaceAll(" ");
        return cleaned.trim();
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
                || SCIENTIFIC_NOTATION_FORMATTING_BUG.matcher(text).find();
    }
}
