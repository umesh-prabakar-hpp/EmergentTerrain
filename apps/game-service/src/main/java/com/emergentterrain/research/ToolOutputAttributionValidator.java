package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-06-08 — fabricated tool-output detector. Flags prose that attributes a
 * specific value to a tool call's OUTPUT — "call_1 returned 452.74", "[call_2]
 * yielded 0.95" — when that value does NOT appear in that call's result.
 *
 * <p>Motivating case P-b72c-77627413: the prose claimed "call_1 returned 452.74
 * as its primary output (a pipeline failure)", but call_1 actually returned
 * 2.18478 and 452.74 appears nowhere in its result JSON. The review panel caught
 * it (contradiction flag), but no deterministic validator did —
 * {@link CitationValidator} only checks numbers carrying an inline {@code
 * [call_N]} marker AFTER them (its lookahead is forward-only), so a running-text
 * "call_N returned X" with the marker before the number escapes.
 *
 * <p>Distinct from {@link UnsourcedClaimValidator} (which flags an unsourced
 * derivative/uncertainty CLAIM) — this flags a fabricated RESULT attribution: a
 * value asserted to BE a named call's output that the trace contradicts.
 *
 * <p>High precision by design: matches only an output-verb immediately after
 * {@code call_N}, only a SALIENT number (a decimal or |value|≥100, never bare
 * structural counts), and passes whenever ANY number in that call's result is
 * within 0.5% of the claim (so honest rounding is fine). Stateless and pure.
 */
public final class ToolOutputAttributionValidator {

    private ToolOutputAttributionValidator() {}

    /**
     * "call_N <output-verb> [up to ~40 non-digit chars] <number>". The verb must
     * be an OUTPUT verb (returned/yielded/produced/…), and the number is the
     * first one within the same clause (no newline, no intervening digit), so a
     * value DERIVED downstream of the call ("call_1 returned 2.18, implying 11")
     * is never attributed to the call.
     */
    private static final Pattern CALL_OUTPUT_CLAIM = Pattern.compile(
            "(?i)\\[?call_(\\d+)\\]?\\s+"
                    // 2026-06-09 — STRONG output verbs only. Dropped shows/showed/
                    // found: those describe what a call REVEALS (a correlation, a
                    // range), not a scalar it returned — FP on "[call_4] shows that
                    // at x=200,000 the correlation is …" (P-b72c-77627448).
                    + "(?:returned|yielded|produced|output|outputs|gave|gives|returns|"
                    + "computed|reported|reports)\\b"
                    + "[^0-9\\n]{0,40}?"
                    // comma-grouped integers ("200,000") match as ONE token so they
                    // aren't truncated to "200" (which then mismatched the result).
                    + "([+-]?[0-9]{1,3}(?:,[0-9]{3})+"
                    + "|[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    /** Any numeric token, for scanning a result JSON. */
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private static final double REL_TOL = 0.005;     // 0.5% relative
    private static final double ABS_TOL = 1e-9;

    /**
     * Returns one reason per fabricated output attribution. A claim passes when
     * the claimed value is within tolerance of some number in the named call's
     * result, or when the claimed value is not salient. Null/empty inputs and a
     * null/empty trace short-circuit to empty (nothing to check against).
     */
    public static List<String> validate(Map<Integer, String> findingsBlocks,
                                        String conclusion,
                                        List<ToolInvocation> trace) {
        List<String> reasons = new ArrayList<>();
        if (trace == null || trace.isEmpty()) return reasons;

        Map<String, String> resultById = new HashMap<>();
        int idx = 1;
        for (ToolInvocation inv : trace) {
            if (inv == null) { idx++; continue; }
            String id = inv.callId() != null ? inv.callId() : ("call_" + idx);
            if (inv.resultJson() != null && !inv.resultJson().isBlank()) {
                resultById.put(id, inv.resultJson());
            }
            idx++;
        }

        StringBuilder bag = new StringBuilder();
        if (findingsBlocks != null) {
            for (String block : new java.util.TreeMap<>(findingsBlocks).values()) {
                if (block != null && !block.isBlank()) bag.append(block).append("\n\n");
            }
        }
        if (conclusion != null && !conclusion.isBlank()) bag.append(conclusion);
        String prose = bag.toString();
        if (prose.isBlank()) return reasons;

        Set<String> flagged = new LinkedHashSet<>();   // dedup by callId+value
        Matcher m = CALL_OUTPUT_CLAIM.matcher(prose);
        while (m.find()) {
            String callId = "call_" + m.group(1);
            String token  = m.group(2);
            double claimed;
            try { claimed = Double.parseDouble(token.replace(",", "")); }
            catch (NumberFormatException e) { continue; }
            if (!isSalient(token, claimed)) continue;   // skip structural counts

            String result = resultById.get(callId);
            if (result == null) {
                if (flagged.add(callId + "|missing")) {
                    reasons.add("tool_output_attribution:missing_call — prose says \""
                            + callId + " returned " + token + "\" but " + callId
                            + " is not in the tool-call trace.");
                }
                continue;
            }
            if (!valueAppearsIn(claimed, result)) {
                if (flagged.add(callId + "|" + token)) {
                    reasons.add("tool_output_attribution:fabricated_output — prose asserts \""
                            + callId + " returned " + token + "\" but that value does not "
                            + "appear in " + callId + "'s result (nearest: "
                            + nearest(claimed, result) + ").");
                }
            }
        }
        return reasons;
    }

    /** Salient = a decimal value or magnitude ≥ 100; bare small integers (counts,
     *  window indices) are too common to attribute and are skipped. */
    private static boolean isSalient(String token, double value) {
        return token.contains(".") || token.contains("e") || token.contains("E")
                || Math.abs(value) >= 100.0;
    }

    private static boolean valueAppearsIn(double claimed, String result) {
        Matcher m = NUMBER.matcher(result);
        while (m.find()) {
            double r;
            try { r = Double.parseDouble(m.group()); }
            catch (NumberFormatException e) { continue; }
            if (withinTol(claimed, r)) return true;
        }
        return false;
    }

    private static boolean withinTol(double a, double b) {
        return Math.abs(a - b) <= REL_TOL * Math.max(Math.abs(a), Math.abs(b)) + ABS_TOL;
    }

    /** The result number closest to the claim, for the reason text ("" if none). */
    private static String nearest(double claimed, String result) {
        String best = "";
        double bestDist = Double.MAX_VALUE;
        Matcher m = NUMBER.matcher(result);
        while (m.find()) {
            double r;
            try { r = Double.parseDouble(m.group()); }
            catch (NumberFormatException e) { continue; }
            double d = Math.abs(r - claimed);
            if (d < bestDist) { bestDist = d; best = m.group(); }
        }
        return best;
    }
}
