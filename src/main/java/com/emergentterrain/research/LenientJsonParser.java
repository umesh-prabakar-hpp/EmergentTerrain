package com.emergentterrain.research;

import java.util.regex.Pattern;

/**
 * Phase 13 E2 — pre-parser repairs for malformed LLM JSON responses.
 *
 * <p>REVIEW_REPORT refresh #1 §1 rec #3 reports {@code rev:parse_failed→fp}
 * fires on 9 of 23 published papers (39%). The student-revision stage emits
 * JSON the strict Jackson parser rejects, the call site falls back to
 * first-pass narration (which carries none of the prof stage's numerics),
 * and the paper publishes as NEEDS_REVISION with substandard prose. Median
 * peer score 0.27 on parse-failed vs 0.42 on clean revisions.
 *
 * <p>This class repairs the six most common malformations observed in the
 * corpus before delegating to Jackson:
 *
 * <ol>
 *   <li>Code-fence wrappers ({@code ```json } &hellip; {@code  ``` }).</li>
 *   <li>Prose preamble before the outermost {@code {} and postamble after
 *       the outermost {@code }}.</li>
 *   <li>Trailing commas before {@code }} or {@code ]}.</li>
 *   <li>Leading {@code +} on numeric values ({@code : +0.5}) — JSON disallows
 *       it, but LLM tool output emits signed magnitudes.</li>
 *   <li>Unclosed strings at EOF (odd count of unescaped {@code "}).</li>
 *   <li>Unbalanced braces — fewer {@code }} than {@code {} at EOF, append
 *       to match.</li>
 * </ol>
 *
 * <p>The repair is conservative: each rule only fires when its specific
 * signature is present. Clean JSON passes through byte-identical. When
 * none of the rules touch the input, {@link RepairResult#repaired()}
 * returns {@code false} — the caller uses this to gate the
 * {@code rev:parse_recovered:from=lenient} call-path stamp.
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class LenientJsonParser {

    private LenientJsonParser() {}

    /** Opening code fence at start of response: ``` or ```json (optional newline). */
    private static final Pattern OPENING_FENCE =
            Pattern.compile("(?s)^\\s*```(?:json|JSON)?\\s*\\n?");

    /** Closing code fence at end of response. */
    private static final Pattern CLOSING_FENCE =
            Pattern.compile("(?s)\\s*```\\s*$");

    /** Trailing comma before a closing brace or bracket — common JSON.parse killer. */
    private static final Pattern TRAILING_COMMA =
            Pattern.compile(",(\\s*[}\\]])");

    /**
     * Leading {@code +} on a numeric value (e.g. {@code : +0.52630} or {@code [+0.5,}).
     * The JSON spec disallows a leading plus, so Jackson throws on signed magnitudes that
     * LLM tool output routinely emits. The lookbehind restricts the match to a
     * value-introducing position ({@code :}, {@code [}, or {@code ,}) and the lookahead
     * requires a following digit (optionally after a dot), so a {@code +} inside a quoted
     * string or a scientific-notation exponent ({@code 1e+9}) is never touched. Group 1
     * preserves any whitespace between the structural char and the dropped sign.
     */
    private static final Pattern LEADING_PLUS =
            Pattern.compile("(?<=[:\\[,])(\\s*)\\+(?=\\.?\\d)");

    /**
     * Repair result — pairs the (possibly-modified) text with a flag
     * indicating whether any repair fired. Callers use the flag to stamp
     * call-path recovery markers.
     */
    public record RepairResult(String text, boolean repaired) {}

    /**
     * Apply the six repair rules in order. Returns the cleaned text plus a
     * boolean indicating whether anything changed. Null input returns
     * {@code (null, false)}; empty/blank input returns it unchanged.
     */
    public static RepairResult repair(String raw) {
        if (raw == null) return new RepairResult(null, false);
        if (raw.isBlank()) return new RepairResult(raw, false);
        String original = raw;
        String s = raw;

        // 1. Strip code fences.
        s = OPENING_FENCE.matcher(s).replaceFirst("");
        s = CLOSING_FENCE.matcher(s).replaceFirst("");

        // 2. Trim prose preamble / postamble around the outermost JSON object.
        //    indexOf('{') / lastIndexOf('}') target the OUTERMOST object;
        //    when the response is something like "Sure, here's the JSON:\n{...}\nLet me know."
        //    this lifts the JSON out cleanly.
        int firstBrace = s.indexOf('{');
        int lastBrace  = s.lastIndexOf('}');
        if (firstBrace > 0 && lastBrace > firstBrace) {
            s = s.substring(firstBrace, lastBrace + 1);
        } else if (firstBrace > 0 && lastBrace == -1) {
            // Truncated response — no closing brace at all. Keep what's there
            // from the first '{' and let rule 5 append closers.
            s = s.substring(firstBrace);
        }

        // 3. Drop trailing commas before } or ].
        s = TRAILING_COMMA.matcher(s).replaceAll("$1");

        // 3b. Strip a leading '+' on numeric values (`: +0.5` / `[+0.5,`). Jackson rejects
        //     leading-plus numbers; LLM tool output emits signed magnitudes like +0.52630.
        //     Only fires after a value-introducing :,[ — never inside a quoted string.
        s = LEADING_PLUS.matcher(s).replaceAll("$1");

        // 4. Close any unclosed string at EOF.
        if (hasUnclosedString(s)) {
            s = s + "\"";
        }

        // 5. Balance braces and brackets by appending closers as needed.
        s = balanceClosers(s);

        return new RepairResult(s, !s.equals(original));
    }

    /**
     * Count unescaped quote characters. Odd → an open string is still
     * dangling at EOF and a closing {@code "} is needed before Jackson
     * will accept the input.
     *
     * <p>Walks the string once tracking escape state: when the previous
     * character was a backslash, the current {@code "} is a literal in the
     * string content and doesn't toggle the open/closed state.
     */
    static boolean hasUnclosedString(String s) {
        int count = 0;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') count++;
        }
        return (count & 1) == 1;
    }

    /**
     * Walk the string tracking brace and bracket depth (ignoring chars
     * inside string literals). At EOF, append the right number of closers
     * in the right order to bring depth back to zero.
     */
    static String balanceClosers(String s) {
        int braceDepth   = 0;
        int bracketDepth = 0;
        // Track the order of openers seen so closers can be appended in the
        // reverse order — `{[` needs to close as `]}` not `}]`.
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped  = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            switch (c) {
                case '{' -> { braceDepth++;   stack.push('{'); }
                case '}' -> { if (braceDepth > 0)   { braceDepth--; stack.pop(); } }
                case '[' -> { bracketDepth++; stack.push('['); }
                case ']' -> { if (bracketDepth > 0) { bracketDepth--; stack.pop(); } }
                default  -> { }
            }
        }
        if (stack.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s);
        while (!stack.isEmpty()) {
            char opener = stack.pop();
            sb.append(opener == '{' ? '}' : ']');
        }
        return sb.toString();
    }
}
