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

    // Phase 10 follow-on (post-redeploy) — bare-phrasing leak patterns.
    // The new room's first papers (P-a328-375 / P-376) carry leak phrasings
    // that the connector-anchored patterns above miss:
    //   "starting position 15 and scale 8.5"   (P-375 — no "the", no "of")
    //   "starting index 53, scale factor 5.3"  (P-376 — no "of")
    // The existing OBSERVED_FROM_POSITION_AND_SCALE and STARTING_INDEX_OF
    // patterns both require the "of" connector. The bare forms read more
    // naturally so the narrator LLM gravitates toward them once they see the
    // numeric values in the refinement accumulator.

    /**
     * "starting position N and scale M.M" / "starting position N and scale
     * factor M.M" — the P-a328-375 leak. Matches without requiring "the" or
     * "of". Optional "factor" head-noun before the scale value.
     */
    private static final Pattern STARTING_POSITION_BARE_AND_SCALE =
            Pattern.compile(CONNECTOR_PREFIX
                    + "starting\\s+position\\s+\\d+\\s+and\\s+scale\\s+(?:factor\\s+)?[\\d.]+",
                    Pattern.CASE_INSENSITIVE);

    /**
     * "starting index N" / "starting cycle index N" — the P-a328-376 leak,
     * sibling of {@link #STARTING_INDEX_OF} with no "of" connector. Optional
     * "(, scale factor M.M)" trailing clause keeps the whole leak phrase
     * removable in a single match so cleanup doesn't have to merge the comma.
     */
    private static final Pattern STARTING_INDEX_BARE =
            Pattern.compile(CONNECTOR_PREFIX
                    + "starting\\s+(?:cycle\\s+)?index\\s+\\d+"
                    + "(?:\\s*,\\s*scale\\s+factor\\s+[\\d.]+)?",
                    Pattern.CASE_INSENSITIVE);

    // Phase 10 — literal `paramN=N` leaks from the refinement-loop accumulator.
    // The May-2026 research/a3289005 refresh #3 found these patterns in ~10 of
    // 21 published papers (Hypothesis text on P-344, P-347, P-355, P-356,
    // P-359, P-362; Findings text on P-344, P-359). All come from the
    // `(param1=N, param2=M.M)` label that ResearchNarrationService embeds in
    // accumulatedFindings — the LLM reads it and parrots the literal form into
    // the published prose. Scrub the surface manifestations here; the
    // accumulator-format fix in ResearchNarrationService removes the source.

    /**
     * Parenthesized comma list — `(param1=N, param2=M.M)`. The most common
     * form. Allow optional spaces around `=` and after `,`. Captures both
     * unicode and ASCII parens since some LLMs emit em-parens.
     */
    private static final Pattern PARAM_PARENTHESIZED_PAIR =
            Pattern.compile(
                    "\\s*\\(\\s*param1\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?\\s*,"
                    + "\\s*param2\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    /** Parenthesized single — `(param1=N)` or `(param2=M.M)`. */
    private static final Pattern PARAM_PARENTHESIZED_SINGLE =
            Pattern.compile(
                    "\\s*\\(\\s*param[12]\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Parenthesized bare label — `(param1)` / `(param2)` — used by the
     * narrator to disambiguate which numeric belongs to which slot, e.g.
     * "scaling parameter of 6.9 (param2) and truncation depth of 22 (param1)"
     * (P-a328-347). Strip the parenthetical entirely.
     */
    private static final Pattern PARAM_PARENTHESIZED_LABEL =
            Pattern.compile("\\s*\\(\\s*param[12]\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Bare assignment — `param1=N` / `param2=M.M` outside any parenthesis,
     * possibly preceded by a connector (", and "). Used inline by P-a328-355
     * ("Experimental sweeps with param1=49 and param2=2.1") and P-a328-362
     * ("initial displacement param1=46° and scale param2=4.4 m").
     */
    private static final Pattern PARAM_BARE_ASSIGN =
            Pattern.compile(CONNECTOR_PREFIX
                    + "param[12]\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?(?:°|m|s|kJ|MPa|GPa|Hz|nm|µm|mm)?",
                    Pattern.CASE_INSENSITIVE);

    // Phase 12 D1 — chain-of-thought leak patterns.
    //
    // P-a328-427 (Doppler) and P-a328-432 (Hall) Findings began with the literal
    // token sequence "<think>\nOkay, let's tackle this query. The user wants me
    // to act as a scientific editor…" and ran until the section boundary
    // without a `</think>` close-tag. This is a sanitizer gap on whichever
    // reviewer model fronts those two papers (both carry `panel:degraded`
    // tails); the model emitted its raw reasoning block because its `<think>`
    // post-processor is single-vendor-specific and didn't run on the
    // cross-vendor path.
    //
    // Run order matters: THINK_BLOCK_CLOSED first (greedy strip of the whole
    // wrapper), then THINK_BLOCK_OPEN_UNCLOSED (everything from the unmatched
    // opening tag through EOF), then OKAY_LETS_TACKLE (defence in depth when
    // the model strips the tag but keeps the opening sentence).

    /** Closed <think>…</think> wrapper, non-greedy. */
    private static final Pattern THINK_BLOCK_CLOSED =
            Pattern.compile("(?is)<think>\\s*.*?</think>\\s*");

    /** Unclosed <think> with no matching close tag — strips to EOF. */
    private static final Pattern THINK_BLOCK_OPEN_UNCLOSED =
            Pattern.compile("(?is)<think>.*$");

    /** Opening sentence of the chain-of-thought leak when the tag is gone. */
    private static final Pattern OKAY_LETS_TACKLE =
            Pattern.compile("(?is)^\\s*Okay,\\s+let'?s\\s+tackle\\s+this\\s+query[^.]*\\.\\s*");

    // Phase 13 E5 — literal `X` placeholder for an unsubstituted uncertainty
    // value, e.g. "87.65% ± X%" (P-a328-467 signature in REVIEW_REPORT
    // refresh #1). The closing-prose template's ± slot uses `X` as a
    // placeholder; when the renderer fails to substitute, the literal "X"
    // lands in the published prose. Strip the bare `± X%?` suffix; the
    // value before it is the recovered measurement.
    //
    // The pattern requires the `± X` prefix so it doesn't touch `X` in
    // unrelated contexts ("The uncertainty was X." stays unchanged because
    // there's no `±` before X). Optional trailing `%` matches both
    // `± X%` (P-467 form) and `± X` (bare X without percent).

    /** Unsubstituted "± X" / "± X%" uncertainty placeholder. */
    private static final Pattern UNSUBSTITUTED_UNCERTAINTY_X =
            Pattern.compile("\\s*±\\s*X\\b\\s*%?", Pattern.CASE_INSENSITIVE);

    // Phase 13 E3 — RLC Q-factor parenthetical regime hint that's frequently
    // mis-paired with the leading noun. Three papers in REVIEW_REPORT
    // refresh #1 (P-438, P-442, P-458) carried lines like:
    //   Underdamped: Q=0.25(over)
    //   Critical:    Q=0.50(crit)
    //   Overdamped:  Q=0.13(under)
    // The (over)/(crit)/(under) parens are redundant with the leading noun
    // AND frequently swapped. Drop them; the leading noun carries the
    // semantic. Run with the rest of the cleanup so any debris is
    // collapsed by CLEANUP_DOUBLE_SPACE.
    private static final Pattern Q_FACTOR_REGIME_PAREN = Pattern.compile(
            "(Q\\s*=\\s*[0-9]+(?:\\.[0-9]+)?)\\s*\\(\\s*(?:under|over|crit)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** Collapses any whitespace/punctuation debris left behind by removals. */
    private static final Pattern CLEANUP_DOUBLE_COMMA   = Pattern.compile("\\s*,\\s*,");
    private static final Pattern CLEANUP_DOUBLE_PERIOD  = Pattern.compile("\\.\\s*\\.");
    private static final Pattern CLEANUP_COMMA_PERIOD   = Pattern.compile(",\\s*\\.");
    private static final Pattern CLEANUP_LEADING_AND    = Pattern.compile("(?<=[.!?]\\s)and\\s+");
    private static final Pattern CLEANUP_DOUBLE_SPACE   = Pattern.compile("\\s{2,}");
    private static final Pattern CLEANUP_SPACE_PUNCT    = Pattern.compile("\\s+([.,;!?])");

    /**
     * Phase 12 D1 — strip chain-of-thought reasoning blocks that leak through
     * the model wrappers on cross-vendor paths.
     *
     * <p>Three patterns, in order: closed {@code <think>…</think>} wrapper,
     * unclosed {@code <think>} that runs to EOF (the load-bearing case for
     * P-a328-427 / P-a328-432), and the bare "Okay, let's tackle this query…"
     * opening sentence that some vendors emit after stripping the tag.
     *
     * <p>Returns the input unchanged when no leak is detected. Returns an empty
     * string when the entire input was a chain-of-thought block — the upstream
     * {@code isBlank} check in {@link UniversityService#publishPaper} then
     * routes the field to its template fallback.
     */
    public static String stripChainOfThought(String text) {
        if (text == null || text.isBlank()) return text;
        String cleaned = THINK_BLOCK_CLOSED.matcher(text).replaceAll("");
        cleaned = THINK_BLOCK_OPEN_UNCLOSED.matcher(cleaned).replaceAll("");
        cleaned = OKAY_LETS_TACKLE.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * Returns {@code text} with harness-parameter leak phrases removed and surrounding
     * grammar cleaned up. Returns {@code null} unchanged so the upstream template
     * fallback in {@link UniversityService#publishPaper} still fires.
     */
    public static String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        String cleaned = text;
        // Phase 12 D1 — strip chain-of-thought leaks (<think>…</think> wrappers,
        // unclosed <think> to EOF, "Okay, let's tackle this query" opener). Runs
        // BEFORE every harness-leak pattern so the granular regexes never see
        // reasoning-block tokens. When the strip empties the field, the
        // upstream template-fallback in UniversityService.publishPaper fires.
        cleaned = stripChainOfThought(cleaned);
        if (cleaned.isBlank()) return cleaned;
        // Phase 13 E5 — strip unsubstituted "± X%" placeholders. Runs after
        // the chain-of-thought strip but before harness-leak patterns so the
        // leftover prose's grammar is still intact for downstream regexes.
        cleaned = UNSUBSTITUTED_UNCERTAINTY_X.matcher(cleaned).replaceAll("");
        // Phase 13 E3 — strip the (over)/(under)/(crit) regime parens on
        // Q-factor values; keep the Q=<value> token intact via capture
        // group 1. The leading noun (Underdamped/Critical/Overdamped) is
        // preserved; the noun-vs-value mismatch (separate failure mode) is
        // flagged by TopicSpecificFindingsValidator.
        cleaned = Q_FACTOR_REGIME_PAREN.matcher(cleaned).replaceAll("$1");
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
        // Phase 10 follow-on — bare-phrasing variants for the new room.
        // STARTING_POSITION_BARE_AND_SCALE strips the whole pair so the
        // simpler STARTING_INDEX_BARE doesn't carve "starting position 15"
        // out of "starting position 15 and scale 8.5" first.
        cleaned = STARTING_POSITION_BARE_AND_SCALE.matcher(cleaned).replaceAll("");
        cleaned = STARTING_INDEX_BARE.matcher(cleaned).replaceAll("");
        // Phase 10 — paramN= literal leaks. Strip the parenthesised pair form
        // first (it's the longest match) so the single / label / bare patterns
        // don't carve it into debris.
        cleaned = PARAM_PARENTHESIZED_PAIR.matcher(cleaned).replaceAll("");
        cleaned = PARAM_PARENTHESIZED_SINGLE.matcher(cleaned).replaceAll("");
        cleaned = PARAM_PARENTHESIZED_LABEL.matcher(cleaned).replaceAll("");
        cleaned = PARAM_BARE_ASSIGN.matcher(cleaned).replaceAll("");
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
        return THINK_BLOCK_CLOSED.matcher(text).find()
                || THINK_BLOCK_OPEN_UNCLOSED.matcher(text).find()
                || OKAY_LETS_TACKLE.matcher(text).find()
                || UNSUBSTITUTED_UNCERTAINTY_X.matcher(text).find()
                || Q_FACTOR_REGIME_PAREN.matcher(text).find()
                || STARTING_AT_POSITION.matcher(text).find()
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
                || STARTING_INDEX_OF.matcher(text).find()
                || STARTING_POSITION_BARE_AND_SCALE.matcher(text).find()
                || STARTING_INDEX_BARE.matcher(text).find()
                || PARAM_PARENTHESIZED_PAIR.matcher(text).find()
                || PARAM_PARENTHESIZED_SINGLE.matcher(text).find()
                || PARAM_PARENTHESIZED_LABEL.matcher(text).find()
                || PARAM_BARE_ASSIGN.matcher(text).find();
    }
}
