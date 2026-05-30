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
 * <p>The allowance is the larger of two measures: a call-count floor of
 * {@code 2 * max(1, trace.size())} (the persisted trace only records calls
 * whose result arrived back from the tool, and the factor of 2 tolerates a
 * couple of internal samples per call), and the actual sweep yield summed
 * across the trace, parsed from each call's output. The yield term matters
 * because a single call legitimately produces many data points:
 * {@code verify_conjecture} reports {@code "across 21 window(s)"},
 * {@code run_predicate_scan} over {@code [300,700)} reports {@code n=400},
 * {@code run_sandboxed_code} reports {@code iters_used=4999}.
 *
 * <p>Comparing a {@code "15 windows"} / {@code "n=400"} claim against the
 * call-count floor alone (10 for a 5-call trace) false-flagged correct papers
 * whose single calls genuinely swept that many points (REVIEW_REPORT b72c4e10
 * rec #1: P-b72c-16 {@code [15]}, P-b72c-18 {@code [15,15,15]}, P-b72c-19
 * {@code [400]}). Folding in the parsed yield fixes those while still catching
 * real fabrication. Concretely:
 *
 * <ul>
 *   <li>{@code "sixteen sampled cases"}, 1 call returning a scalar (yield 0):
 *       16 over max(2*1, 0) -&gt; flag.</li>
 *   <li>{@code "n=400"}, a {@code run_predicate_scan} reporting {@code n=400}:
 *       400 within max(2*5, 400) -&gt; pass (backed by real yield).</li>
 *   <li>{@code "15 windows"}, a {@code verify_conjecture} reporting
 *       {@code "across 21 window(s)"}: 15 within max(2*5, 21) -&gt; pass.</li>
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
     * Sweep-yield signals in a tool's output (or args) — the real number of
     * data points a single call produced. Each is matched against the
     * concatenation of {@code argsJson} and {@code resultJson} per call:
     * <ul>
     *   <li>{@code "across 21 window(s)"} — {@code verify_conjecture};</li>
     *   <li>{@code "n=400"} — {@code run_predicate_scan} survey size, and
     *       {@code run_sandboxed_code} "{@code recorded n=7}";</li>
     *   <li>{@code "iters_used=4999"} — {@code run_sandboxed_code} loop;</li>
     *   <li>{@code "num_windows": 30} — verify-conjecture args.</li>
     * </ul>
     * The {@code \\bn\\s*=} boundary avoids matching {@code min=}/{@code mean=}.
     */
    private static final Pattern[] YIELD_SIGNALS = {
            Pattern.compile("(?i)across\\s+(\\d{1,7})\\s+window"),
            Pattern.compile("(?i)\\bn\\s*=\\s*(\\d{1,7})"),
            Pattern.compile("(?i)iters_used\\s*=\\s*(\\d{1,7})"),
            Pattern.compile("(?i)\"?num_windows\"?\\s*[:=]\\s*(\\d{1,7})"),
    };

    /**
     * Returns non-empty reason list when the prose asserts a sample
     * count {@code N} exceeding {@code 2 × max(1, trace.size())}.
     * Multiple offending claims fold into one summary reason so the
     * call-path stamp stays compact.
     */
    public static List<String> validate(String findings, List<ToolInvocation> trace) {
        if (findings == null || findings.isBlank()) return List.of();
        if (trace == null || trace.isEmpty()) return List.of();
        // Allowance = larger of the call-count floor (2 per successful call)
        // and the data points the trace actually produced (parsed from the
        // tool outputs). The floor alone false-flagged correct papers whose
        // single calls legitimately swept many windows / scanned n integers
        // (REVIEW_REPORT b72c4e10 §1 rec #1).
        long allowance = Math.max(2L * Math.max(1, trace.size()), traceDataPoints(trace));
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
                        + "%d successful call(s) yielding at most %d data point(s) "
                        + "(Phase 14 F3 allowance = max of 2x calls and observed sweep "
                        + "yield). Either the narrator inflated the sweep size or the "
                        + "trace is incomplete.",
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

    /**
     * Total data points the trace actually produced — the sum over calls of
     * each call's largest sweep-yield signal (windows / n / iters). Used as
     * the upper measure of the sample-size allowance so a Findings claim
     * backed by genuine sweep output is not mistaken for fabrication.
     */
    private static long traceDataPoints(List<ToolInvocation> trace) {
        long total = 0;
        for (ToolInvocation call : trace) {
            if (call == null) continue;
            StringBuilder text = new StringBuilder();
            if (call.argsJson() != null) text.append(call.argsJson()).append(' ');
            if (call.resultJson() != null) text.append(call.resultJson());
            long best = 0;
            for (Pattern signal : YIELD_SIGNALS) {
                best = Math.max(best, maxMatch(signal, text));
            }
            total += best;
        }
        return total;
    }

    /** Largest first-group integer {@code pattern} matches in {@code text}, or 0. */
    private static long maxMatch(Pattern pattern, CharSequence text) {
        Matcher m = pattern.matcher(text);
        long best = 0;
        while (m.find()) {
            try {
                best = Math.max(best, Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // Defensive: groups are \d{1,7}, so this can't fire.
            }
        }
        return best;
    }
}
