package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 14 F3 — flag papers whose Findings prose claims a sample count
 * (e.g. "sixteen sampled cases", "n=40 windows", "5 measured trials")
 * unsupported by the persisted tool-call trace.
 *
 * <p>REVIEW_REPORT refresh #2 §1 rec #4 named three corpus papers carrying
 * this pattern: P-a328-494 ("sweep over [591,650]" backed by one call),
 * P-a328-507 ("sixteen sampled cases" backed by one call), and P-a328-512
 * ("16 measured Ea values" backed by two calls). The narrator
 * inflates the apparent sample size to lend the Findings prose
 * sweep-like credibility it doesn't actually have.
 *
 * <p>The validator treats {@code trace.size()} as the actual successful
 * call count — the persisted trace only records calls whose result
 * arrived back from the tool (failed / rejected sandbox attempts never
 * make it into the trace). A slack factor of {@code 2 × trace.size()}
 * tolerates legitimate sweep output where each call returns several
 * internal samples, while still catching 16-vs-1 fabrication. Concretely:
 *
 * <ul>
 *   <li>Findings says {@code "sixteen sampled cases"}, trace has 1 call:
 *       16 &gt; 2·1 → flag.</li>
 *   <li>Findings says {@code "4 measured cases"}, trace has 2 calls:
 *       4 ≤ 2·2 → pass (sweep-with-internal-samples within slack).</li>
 *   <li>Findings says {@code "n=40 windows"}, trace has 5 calls:
 *       40 &gt; 2·5 → flag.</li>
 * </ul>
 *
 * <p>Null or empty trace short-circuits to empty (no information to
 * validate against; legacy / Ollama-fallback / JSON-only papers
 * legitimately have no trace).
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class SinglePointSweepValidator {

    private SinglePointSweepValidator() {}

    /**
     * Digit-form sample-size claim. Matches {@code "n = 40"},
     * {@code "16 measured cases"}, {@code "5 windows"}, etc. The two
     * capture groups are alternates — exactly one fires per match.
     */
    private static final Pattern DIGIT_SAMPLE_CLAIM = Pattern.compile(
            "(?i)\\b(?:n\\s*=\\s*(\\d{1,4})"
                    + "|(\\d{1,4})\\s*(?:sampled?|measured|tested|"
                    + "cases?|windows?|samples?|runs?|trials?|points?|"
                    + "evaluations?|values?|parameter[\\s-]combinations?))\\b");

    /**
     * Word-form sample-size claim ("sixteen sampled cases"). Restricted
     * to the small-cardinal range that the corpus's narrator actually
     * emits — extending to "hundred" / "thousand" is unnecessary and
     * tends to false-positive on real prose like "hundreds of values".
     */
    private static final Pattern WORD_SAMPLE_CLAIM = Pattern.compile(
            "(?i)\\b(two|three|four|five|six|seven|eight|nine|ten|"
                    + "eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
                    + "seventeen|eighteen|nineteen|twenty|thirty|forty|"
                    + "fifty|sixty|seventy|eighty|ninety)\\s+"
                    + "(?:sampled?|measured|tested|cases?|windows?|samples?|"
                    + "runs?|trials?|points?|evaluations?|values?|"
                    + "parameter[\\s-]combinations?)\\b");

    /**
     * Returns non-empty reason list when the prose asserts a sample
     * count {@code N} exceeding {@code 2 × max(1, trace.size())}.
     * Multiple offending claims fold into one summary reason so the
     * call-path stamp stays compact.
     */
    public static List<String> validate(String findings, List<ToolInvocation> trace) {
        if (findings == null || findings.isBlank()) return List.of();
        if (trace == null || trace.isEmpty()) return List.of();
        int allowance = 2 * Math.max(1, trace.size());
        List<Integer> overclaims = new ArrayList<>();

        Matcher digit = DIGIT_SAMPLE_CLAIM.matcher(findings);
        while (digit.find()) {
            String captured = digit.group(1) != null ? digit.group(1) : digit.group(2);
            try {
                int n = Integer.parseInt(captured);
                if (n > allowance) overclaims.add(n);
            } catch (NumberFormatException ignored) {
                // Defensive: regex restricted to \d{1,4}, so this can't fire.
            }
        }

        Matcher word = WORD_SAMPLE_CLAIM.matcher(findings);
        while (word.find()) {
            int n = wordToInt(word.group(1));
            if (n > allowance) overclaims.add(n);
        }

        if (overclaims.isEmpty()) return List.of();
        return List.of(String.format(
                "Findings claims %s samples, but the tool-call trace records only "
                        + "%d successful call(s) (Phase 14 F3 slack allows up to %d). "
                        + "Either the narrator inflated the sweep size or the trace is "
                        + "incomplete.",
                overclaims, trace.size(), allowance));
    }

    /**
     * Structured-findings overload. The single-paper sample-size
     * discipline applies to the concatenation of every block's prose.
     */
    public static List<String> validate(Map<Integer, String> findingsMap,
                                         List<ToolInvocation> trace) {
        if (findingsMap == null || findingsMap.isEmpty()) return List.of();
        return validate(MagnitudeRangeValidator.joinBlocks(findingsMap), trace);
    }

    private static int wordToInt(String word) {
        return switch (word.toLowerCase()) {
            case "two"       -> 2;
            case "three"     -> 3;
            case "four"      -> 4;
            case "five"      -> 5;
            case "six"       -> 6;
            case "seven"     -> 7;
            case "eight"     -> 8;
            case "nine"      -> 9;
            case "ten"       -> 10;
            case "eleven"    -> 11;
            case "twelve"    -> 12;
            case "thirteen"  -> 13;
            case "fourteen"  -> 14;
            case "fifteen"   -> 15;
            case "sixteen"   -> 16;
            case "seventeen" -> 17;
            case "eighteen"  -> 18;
            case "nineteen"  -> 19;
            case "twenty"    -> 20;
            case "thirty"    -> 30;
            case "forty"     -> 40;
            case "fifty"     -> 50;
            case "sixty"     -> 60;
            case "seventy"   -> 70;
            case "eighty"    -> 80;
            case "ninety"    -> 90;
            default          -> -1;
        };
    }
}
