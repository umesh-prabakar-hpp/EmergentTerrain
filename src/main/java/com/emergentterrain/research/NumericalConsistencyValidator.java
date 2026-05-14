package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5 B1 — flag papers whose Conclusion contradicts its own Hypothesis or
 * Findings on a numerical claim.
 *
 * <p>Three corpus-documented failure patterns this v1 catches:
 * <ul>
 *   <li><b>"close to N×" / "approximately N" / "near N" with no nearby actual</b>.
 *       P-a328-14 claimed observed multipliers of 1.07× and 1.11× were "close
 *       to the expected 2× value." Flag when a comparison anchor (N) has no
 *       observed value within ±20%.</li>
 *   <li><b>Hypothesis range vs Conclusion value mismatch.</b> P-a328-50's
 *       Hypothesis said "near 380"; Conclusion said "starting 710." P-a328-61's
 *       Hypothesis said "near 100"; Conclusion said "starting 348." Flag when
 *       Hypothesis's "near X" / "around X" disagrees with Conclusion's
 *       "starting Y" / "at Y" / "≈Y" by &gt; 50%.</li>
 *   <li><b>Conclusion threshold off the Findings grid.</b> P-a328-60: Conclusion
 *       said "1% threshold at ~25°" but the table showed 0.77% at 25° and
 *       1.20% at 30° — the conclusion's threshold should land near a Findings
 *       row whose value is within ±0.2× of the claim. Flag when no nearby
 *       Findings entry exists.</li>
 * </ul>
 *
 * <p>Stateless and pure: returns the list of textual reasons. Empty list = no
 * inconsistency. Caller (UniversityService.publishPaper) downgrades to
 * NEEDS_REVISION when non-empty and the flag is on.
 *
 * <p>Tolerances chosen to be loose enough that legitimate "approximately"
 * language passes: 20% for "close to" comparisons, 50% for Hypothesis-vs-Conclusion
 * coord mismatches (the documented examples are 87% and 248% off — far above
 * any reasonable approximation band).
 */
public final class NumericalConsistencyValidator {

    private NumericalConsistencyValidator() {}

    /**
     * Catches "(close to|approximately|near|nearly|roughly) [the] [expected] N[×%°]"
     * — the comparison anchor. We treat the captured number as the "expected" value.
     */
    private static final Pattern COMPARISON_ANCHOR = Pattern.compile(
            "(?i)\\b(?:close to|approximately|near(?!\\s+\\d+(?:\\.\\d+)?\\s*,)|nearly|roughly)\\s+"
                    + "(?:the\\s+)?(?:expected\\s+)?"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*([×%°]?)");

    /**
     * Captures "near {N}" / "around {N}" / "≈{N}" in the Hypothesis. Used to anchor
     * the parameter range a Conclusion's coord should land near.
     */
    private static final Pattern HYPOTHESIS_NEAR = Pattern.compile(
            "(?i)\\b(?:near|around|approximately|≈)\\s+([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Captures "starting {at} {N}" / "at {N}" / "≈{N}" / "found {at} {N}" in the
     * Conclusion. Bounded to integers ≥ 3 chars to avoid matching every "at 5"
     * casual reference.
     */
    private static final Pattern CONCLUSION_COORD = Pattern.compile(
            "(?i)\\b(?:starting\\s+(?:at\\s+)?|found\\s+(?:at\\s+)?|peaks?\\s+(?:at\\s+)?|\\bat\\s+|≈\\s*)"
                    + "([0-9]{3,}(?:\\.[0-9]+)?)");

    /** Any bare numeric token. Used to scan nearby actuals. */
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Phase 7 F5.5 — a "salient" number is one with a decimal point and ≥2 digits
     * after, OR any value in scientific notation. Plain integers and one-digit
     * decimals are excluded so the check doesn't fire on prose like "2 of 3
     * reviewers", "approximately 100", or "the first 5 iterations". Matches:
     * 3.14, 42.30, 1.2e-3, 2.998e8, 1E10, 1.0e+9. Skips: 1, 100, 1.5.
     */
    private static final Pattern SALIENT_NUMBER = Pattern.compile(
            "\\b("
                    + "[0-9]+\\.[0-9]{2,}(?:[eE][+-]?[0-9]+)?"  // 3.14 / 42.30 / 1.20e-3
                    + "|[0-9]+(?:\\.[0-9]+)?[eE][+-]?[0-9]+"     // 1e-3 / 2.998e8 / 1E10
                    + ")\\b");

    /** Tolerance for Check C numeric matching (1%). */
    private static final double F5_5_RELATIVE_TOLERANCE = 0.01;

    /**
     * Validates the three checks above. Returns a list of textual reasons for
     * downstream telemetry (one entry per inconsistency).
     */
    public static List<String> validate(String hypothesis, String findings, String conclusion) {
        List<String> reasons = new ArrayList<>();
        if (hypothesis == null || findings == null || conclusion == null) return reasons;

        // Check A — "close to N" anchor that has no nearby observed value within ±20%.
        findCloseToFarFromObserved(findings, conclusion, reasons);

        // Check B — Hypothesis "near X" vs Conclusion "starting Y" mismatch.
        findRangeMismatch(hypothesis, conclusion, reasons);

        return reasons;
    }

    /**
     * Phase 7 F2 — structured-findings overload. Operates on the concatenation of
     * every block's prose — semantically equivalent to {@link #validate(String,
     * String, String)} on the joined String. Null / empty map degrades to a null
     * findings, which the String overload short-circuits.
     */
    public static List<String> validate(String hypothesis,
                                         java.util.Map<Integer, String> findingsMap,
                                         String conclusion) {
        return validate(hypothesis, MagnitudeRangeValidator.joinBlocks(findingsMap), conclusion);
    }

    /**
     * Phase 7 F5.5 — trace-aware overload. Runs the F2 String/Map checks AND a new
     * Check C that catches "salient" numbers in the Findings prose that don't trace
     * to any source the LLM could legitimately have drawn from:
     * <ul>
     *   <li>numbers in the Hypothesis (the bot's pre-registered range / anchor),</li>
     *   <li>numbers in the Conclusion (so the LLM may propagate its own claim,
     *       though this is a small loosening),</li>
     *   <li>numbers in any {@code resultJson} of the agentic professor's tool calls
     *       (Tier 2 trace persisted from F5.3 / F5.4).</li>
     * </ul>
     * A "salient" number is one with a decimal point and at least two digits after
     * the point, or any value in scientific notation. Plain integers and one-digit
     * decimals are excluded because they're typically counts / indices / round
     * references rather than computed quantities. Tolerance ±1% to allow
     * round-trip formatting drift (1229 in JSON vs "1230" in prose).
     *
     * <p>Empty / null trace short-circuits: Check C only fires when the paper has
     * a non-empty tool-call trace, since for trace-less papers (JSON-path,
     * Ollama-fallback, legacy rows) the seed engine numbers are the prose's only
     * source and we have no visibility on those here.
     */
    public static List<String> validate(String hypothesis,
                                         java.util.Map<Integer, String> findingsMap,
                                         String conclusion,
                                         java.util.List<com.emergentterrain.llm.ToolInvocation> toolCallTrace) {
        List<String> reasons = validate(hypothesis, findingsMap, conclusion);
        if (toolCallTrace == null || toolCallTrace.isEmpty()) return reasons;
        String findings = MagnitudeRangeValidator.joinBlocks(findingsMap);
        findUnsourcedFindingsNumbers(hypothesis, findings, conclusion, toolCallTrace, reasons);
        return reasons;
    }

    /**
     * For each "close to N" / "approximately N" anchor in the Conclusion, look at
     * numeric tokens in the surrounding Findings section. If none lands within
     * ±20% of N, flag a "claim-vs-data" inconsistency.
     */
    private static void findCloseToFarFromObserved(String findings, String conclusion,
                                                    List<String> reasons) {
        Matcher m = COMPARISON_ANCHOR.matcher(conclusion);
        while (m.find()) {
            double anchor = Double.parseDouble(m.group(1));
            if (anchor == 0) continue;
            String unit = m.group(2);
            // Look for any number in Findings within 20% of the anchor — same unit
            // suffix preferred but not required.
            boolean foundNearby = false;
            Matcher tokens = NUMERIC_TOKEN.matcher(findings);
            while (tokens.find()) {
                double observed = Double.parseDouble(tokens.group(1));
                if (Math.abs(observed - anchor) / Math.abs(anchor) <= 0.20) {
                    foundNearby = true;
                    break;
                }
            }
            if (!foundNearby) {
                reasons.add(String.format(
                        "Conclusion claims values are 'close to %s%s' but no Findings value lands within ±20%% of %s.",
                        m.group(1), unit, m.group(1)));
            }
        }
    }

    /**
     * For each "near X" / "around X" the Hypothesis declares, check whether the
     * Conclusion's "starting Y" / "at Y" coord lands within ±50% of X. The
     * documented corpus offenders (P-a328-50 at 87%, P-a328-61 at 248%) are
     * well past this band — anything within 50% is treated as a legitimate
     * refinement of the hypothesis range.
     */
    private static void findRangeMismatch(String hypothesis, String conclusion,
                                           List<String> reasons) {
        List<Double> hypAnchors = new ArrayList<>();
        Matcher hyp = HYPOTHESIS_NEAR.matcher(hypothesis);
        while (hyp.find()) hypAnchors.add(Double.parseDouble(hyp.group(1)));
        if (hypAnchors.isEmpty()) return;

        Matcher con = CONCLUSION_COORD.matcher(conclusion);
        while (con.find()) {
            double coord = Double.parseDouble(con.group(1));
            // Hypothesis range mismatch only fires if NO hypothesis anchor lands
            // within ±50% of the conclusion coord.
            boolean foundCompatible = false;
            for (double anchor : hypAnchors) {
                if (anchor == 0) continue;
                if (Math.abs(coord - anchor) / Math.abs(anchor) <= 0.50) {
                    foundCompatible = true;
                    break;
                }
            }
            if (!foundCompatible) {
                reasons.add(String.format(
                        "Conclusion coord %.0f falls outside the Hypothesis 'near %s' band (±50%%).",
                        coord, formatList(hypAnchors)));
            }
        }
    }

    /**
     * Phase 7 F5.5 — for every salient number in the findings prose, verify that
     * at least one source (hypothesis, conclusion, or any tool-call result)
     * contains a number within ±1% of it. Otherwise flag as an "unsourced numeric
     * claim" — the LLM appears to have introduced a precise value that no
     * deterministic computation backed.
     */
    private static void findUnsourcedFindingsNumbers(
            String hypothesis, String findings, String conclusion,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (findings == null || findings.isBlank()) return;
        List<Double> permitted = new ArrayList<>();
        collectAllNumbers(hypothesis, permitted);
        collectAllNumbers(conclusion, permitted);
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv != null && inv.resultJson() != null) {
                collectAllNumbers(inv.resultJson(), permitted);
            }
        }
        if (permitted.isEmpty()) return;

        Matcher m = SALIENT_NUMBER.matcher(findings);
        while (m.find()) {
            double n;
            try { n = Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ex) { continue; }
            if (!isPermittedNumber(n, permitted)) {
                reasons.add(String.format(
                        "Findings cites salient value '%s' that is not within ±1%% of any number in "
                                + "the hypothesis, conclusion, or tool-call trace results.",
                        m.group(1)));
            }
        }
    }

    private static void collectAllNumbers(String text, List<Double> sink) {
        if (text == null) return;
        // Use a broader regex than NUMERIC_TOKEN — also picks up scientific notation
        // in JSON / prose so the "permitted" set matches whatever the LLM might cite.
        Matcher m = Pattern.compile(
                "[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(text);
        while (m.find()) {
            try { sink.add(Double.parseDouble(m.group())); }
            catch (NumberFormatException ignored) {}
        }
    }

    private static boolean isPermittedNumber(double value, List<Double> permitted) {
        for (double p : permitted) {
            if (p == 0.0) {
                if (value == 0.0) return true;
                continue;
            }
            if (Math.abs(value - p) / Math.abs(p) <= F5_5_RELATIVE_TOLERANCE) return true;
        }
        return false;
    }

    private static String formatList(List<Double> values) {
        if (values.size() == 1) {
            double v = values.get(0);
            return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            sb.append(v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v));
            if (i < values.size() - 1) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }
}
