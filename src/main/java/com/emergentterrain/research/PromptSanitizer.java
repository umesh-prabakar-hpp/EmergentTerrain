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
        cleaned = STARTING_AT_POSITION.matcher(cleaned).replaceAll("");
        cleaned = USES_A_SCALE_OF.matcher(cleaned).replaceAll("");
        cleaned = QUALITY_SCORE_SENTENCE.matcher(cleaned).replaceAll("");
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
                || QUALITY_SCORE_SENTENCE.matcher(text).find();
    }
}
