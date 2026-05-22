package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-05-21 review §1 rec #4 follow-up — catches the P-a328-871 exact
 * signature where the Hypothesis prose declares one baseline value (e.g.
 * "close to the pre-registered baseline value of 0.300") while the
 * {@code run_predicate_scan} tool call was actually invoked with a
 * different baseline (e.g. {@code baseline=0}). The narrator wrote a
 * probability-shaped expectation into the prose but never wired it into
 * the tool args — so the tool result's "deviation from baseline" measure
 * is nonsense, and the published paper looks like it tested 0.300 when
 * the simulator never did.
 *
 * <p>Complements {@link BaselineMetricRangeValidator}, which catches the
 * inline shape mismatch on the tool-call args. The two together cover:
 * <ul>
 *   <li>BMR: args side — baseline=0.300 against mean_of:* metric.</li>
 *   <li>PBD: prose-vs-args side — prose says 0.300, args say 0.</li>
 * </ul>
 *
 * <p>Conservative thresholding: 1 % relative difference with a floor of
 * {@code 1.0} on the denominator. Prevents over-flagging on very small
 * baselines while still catching e.g. {@code prose=0.300 vs args=0}
 * (relative diff 0.3, flagged) and allowing {@code prose=79 vs args=79.5}
 * (relative diff 0.006, not flagged).
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class ProseBaselineDriftValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 1 % relative difference, with a floor of 1.0 on the denominator. */
    private static final double REL_TOLERANCE = 0.01;
    private static final double DENOM_FLOOR  = 1.0;

    /**
     * Pulls baseline numerics out of hypothesis prose. Matches forms:
     * <ul>
     *   <li>"baseline of 0.300"</li>
     *   <li>"baseline value of 0.300"</li>
     *   <li>"pre-registered baseline value of 0.300"</li>
     *   <li>"baseline ≈ 0.300", "baseline = 0.300", "baseline is 0.300"</li>
     *   <li>"baseline approximately 0.300", "baseline close to 0.300"</li>
     * </ul>
     * Skips values immediately followed by {@code %} (percentages are a
     * different shape and probably intentional).
     */
    private static final Pattern PROSE_BASELINE = Pattern.compile(
            "(?i)\\b(?:pre[-\\u2010\\u2011]registered\\s+)?baseline(?:\\s+value)?\\s+"
                    + "(?:of\\s+|=\\s*|is\\s+|\\u2248\\s*|approximately\\s+|close\\s+to\\s+)?"
                    + "([0-9]+(?:\\.[0-9]+)?)\\b(?!\\s*%)");

    private ProseBaselineDriftValidator() {}

    /**
     * Returns a list of human-readable mismatch reasons; empty when no
     * drift is detectable. Returns empty (not flagged) on any of:
     * <ul>
     *   <li>null hypothesis or null/empty trace</li>
     *   <li>no prose-baseline mentions in hypothesis</li>
     *   <li>no {@code run_predicate_scan} entries in trace</li>
     *   <li>every prose value matches at least one arg value within tolerance</li>
     * </ul>
     */
    public static List<String> validate(String hypothesis,
                                         List<ToolInvocation> trace) {
        if (hypothesis == null || trace == null || trace.isEmpty())
            return List.of();

        List<Double> proseBaselines = extractProseBaselines(hypothesis);
        if (proseBaselines.isEmpty()) return List.of();

        List<Double> argBaselines = extractArgBaselines(trace);
        if (argBaselines.isEmpty()) return List.of();

        List<String> reasons = new ArrayList<>();
        for (double p : proseBaselines) {
            boolean matched = false;
            for (double a : argBaselines) {
                if (closeEnough(p, a)) { matched = true; break; }
            }
            if (!matched) {
                reasons.add(String.format(
                        "Hypothesis prose cites baseline=%s but no run_predicate_scan "
                                + "call used a matching value (trace args: %s; 2026-05-21 "
                                + "review §1 rec #4 follow-up).",
                        format(p), formatList(argBaselines)));
            }
        }
        return reasons;
    }

    private static List<Double> extractProseBaselines(String prose) {
        List<Double> out = new ArrayList<>();
        Matcher m = PROSE_BASELINE.matcher(prose);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return out;
    }

    private static List<Double> extractArgBaselines(List<ToolInvocation> trace) {
        List<Double> out = new ArrayList<>();
        for (ToolInvocation t : trace) {
            if (!"run_predicate_scan".equals(t.functionName())) continue;
            if (t.argsJson() == null) continue;
            try {
                JsonNode node = MAPPER.readTree(t.argsJson());
                if (node.has("baseline")) {
                    out.add(node.get("baseline").asDouble());
                }
            } catch (Exception ignored) { /* malformed args — skip */ }
        }
        return out;
    }

    private static boolean closeEnough(double prose, double arg) {
        double denom = Math.max(Math.max(Math.abs(prose), Math.abs(arg)), DENOM_FLOOR);
        return Math.abs(prose - arg) / denom < REL_TOLERANCE;
    }

    private static String format(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static String formatList(List<Double> xs) {
        if (xs.isEmpty()) return "[]";
        List<String> parts = new ArrayList<>(xs.size());
        for (double x : xs) parts.add(format(x));
        Collections.sort(parts);
        return "[" + String.join(", ", parts) + "]";
    }
}
