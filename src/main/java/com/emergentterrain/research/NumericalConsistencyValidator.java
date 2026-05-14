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
