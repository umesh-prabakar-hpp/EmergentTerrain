package com.emergentterrain.research;

import java.util.regex.Pattern;

/**
 * Detects whether a numeric token or range literal sits inside a sentence that
 * explicitly marks it as NOT an executed result — negated execution ("was not
 * performed", "cannot be confirmed from the present trace") or named future
 * work ("a natural next step", "would require"). Such sentences are honest
 * limitation/next-step narration that reviewers and revision rules actively
 * request; the example values and candidate ranges inside them are not claims,
 * so provenance/scope validators must not flag them. Two live papers were
 * demoted exactly this way: an example parameter inside a "no sensitivity
 * analysis … was performed" sentence (manifest miss), and a candidate range
 * inside a "cannot be confirmed from the present trace … a natural next step"
 * sentence (scope overreach) — in the latter case the panel itself had asked
 * for the sentence.
 *
 * <p><b>Deliberately narrow:</b> an example marker alone ("e.g.") does NOT
 * exempt — the sentence must carry a strong negation/future-work marker, so a
 * value asserted as a result stays flagged even when wrapped in an example.
 * Every marker added here erodes the fabrication catch; keep the list short.
 *
 * <p>Stateless, no Spring; shared by {@code NumericalConsistencyValidator}
 * (scope overreach) and {@code ManifestTraceabilityValidator} (salient-value
 * misses).
 */
public final class HypotheticalContext {

    private HypotheticalContext() {}

    /**
     * Strong markers that a sentence describes something NOT executed /
     * NOT establishable from the trace, or names future work.
     */
    private static final Pattern STRONG_MARKER = Pattern.compile(
            "(?i)(?:cannot|could\\s+not|can\\s+not)\\s+be\\s+\\w+ed\\b"
                    + "|(?:was|were|is|are|has|have)\\s+not\\s+(?:been\\s+)?"
                    +   "(?:performed|tested|run|executed|evaluated|computed|measured"
                    +   "|scanned|sampled|conducted|confirmed|included|present|covered)\\b"
                    // Gap may contain abbreviation dots and commas ("(e.g., 1,000 vs. 10,000)")
                    // — the match runs inside one extracted sentence, so '.' here is never a
                    // sentence boundary; only clause/sentence terminators end the gap.
                    + "|\\bno\\s+[^;:!?\\n]{0,100}?\\b(?:was|were)\\s+"
                    +   "(?:performed|conducted|run|computed|carried\\s+out|included)\\b"
                    + "|\\bdid\\s+not\\s+(?:run|test|evaluate|execute|include|perform|cover|scan)\\b"
                    + "|\\bnatural\\s+next\\s+step\\b"
                    + "|\\bfuture\\s+work\\b"
                    + "|\\bwould\\s+require\\b"
                    + "|\\bremains?\\s+(?:open|untested|unexplored|unverified)\\b"
                    + "|\\bif\\s+computational\\s+limits\\b");

    /**
     * Abbreviations whose trailing {@code ". "} is not a sentence boundary
     * ("vs. 10,000", "e.g. 500"). Checked against the letters immediately
     * before the dot.
     */
    private static final Pattern ABBREVIATION_BEFORE_DOT = Pattern.compile(
            "(?i)(?:\\bvs|\\be\\.g|\\bi\\.e|\\betc|\\bcf|\\bca|\\bapprox)$");

    /**
     * True when the token at {@code [start, end)} of {@code text} lies in a
     * sentence carrying a strong hypothetical/negated-execution marker.
     */
    public static boolean isHypothetical(String text, int start, int end) {
        if (text == null || text.isEmpty() || start < 0 || end > text.length() || start >= end) {
            return false;
        }
        return STRONG_MARKER.matcher(sentenceAround(text, start, end)).find();
    }

    /**
     * The sentence containing {@code [start, end)}. Boundary is a newline or
     * a {@code ". "} (period+space — never a bare '.', which would split
     * decimals) that does not close a known abbreviation; capped at 400 chars
     * each side so a degenerate unpunctuated block stays cheap.
     */
    static String sentenceAround(String text, int start, int end) {
        int lo = Math.max(0, start - 400);
        int s = lo;
        for (int i = start - 1; i > lo; i--) {
            char c = text.charAt(i);
            if (c == '\n'
                    || (c == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' '
                        && !isAbbreviationDot(text, i))) {
                s = i + 1;
                break;
            }
        }
        int hi = Math.min(text.length(), end + 400);
        int e = hi;
        for (int i = end; i < hi; i++) {
            char c = text.charAt(i);
            if (c == '\n'
                    || (c == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' '
                        && !isAbbreviationDot(text, i))) {
                e = i;
                break;
            }
        }
        return text.substring(s, e);
    }

    private static boolean isAbbreviationDot(String text, int dotIdx) {
        int from = Math.max(0, dotIdx - 8);
        return ABBREVIATION_BEFORE_DOT.matcher(text.substring(from, dotIdx)).find();
    }
}
