package com.emergentterrain.research;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 14 F4 — flag papers whose Conclusion makes a directional claim
 * (confirmed/refuted/within/outside) that contradicts the Findings number
 * relative to the Hypothesis bound.
 *
 * <p>REVIEW_REPORT refresh #2 §1 rec #3 named three corpus papers carrying
 * this pattern:
 * <ul>
 *   <li>P-a328-491 Findings: max deviation 0.694% (well within 5% bound).
 *       Conclusion: "hypothesis ruled out" — the actual is <em>within</em>
 *       the bound, so "ruled out" is the wrong direction.</li>
 *   <li>P-a328-496 Conclusion talks about a quality score the
 *       pre-registered Hypothesis never mentioned.</li>
 *   <li>P-a328-511 Conclusion cites Tc=353K while the only successful
 *       call ran Tc=339K — a different parameter slice than Findings
 *       supports.</li>
 * </ul>
 *
 * <p>This validator targets the structurally-checkable subset: Hypothesis
 * supplies a numeric bound, Findings supplies a comparable actual, and
 * the Conclusion's polarity vocabulary makes a within/outside claim. When
 * the three agree on the bound's quantity but disagree on the polarity,
 * the validator flags.
 *
 * <p>The validator is intentionally conservative: any ambiguity (no
 * bound, no actual, no polarity word) returns empty. False negatives are
 * preferred over false positives because the gate routes to
 * NEEDS_REVISION, not REJECTED.
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class DirectionOfFindingValidator {

    private DirectionOfFindingValidator() {}

    /**
     * Hypothesis bound — the upper limit of an allowed deviation /
     * tolerance. Matches "within X%", "≤ X%", "less than X%",
     * "below X", "at most X". Group 1 is the numeric magnitude.
     */
    private static final Pattern HYPOTHESIS_BOUND = Pattern.compile(
            "(?i)\\b(?:within|≤|<=|<|less\\s+than|below|"
                    + "at\\s+most|no\\s+more\\s+than|under)\\s+"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*%");

    /**
     * Findings actual — typical narrator phrasings for the observed
     * deviation / value. Matches "max deviation 0.694%",
     * "deviation of 0.694%", "observed 0.694%", "0.694% deviation".
     */
    private static final Pattern FINDINGS_DEVIATION = Pattern.compile(
            "(?i)\\b(?:max(?:imum)?\\s+deviation|deviation\\s+(?:of|was|=)|"
                    + "observed(?:\\s+deviation)?|actual\\s+deviation|"
                    + "measured\\s+(?:at|=)?)\\s*"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*%"
                    + "|"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*%\\s+deviation");

    /**
     * Conclusion polarity vocabulary. The narrator's directional verdict
     * relative to the Hypothesis bound. Two camps:
     *
     * <ul>
     *   <li><b>WITHIN</b>: "confirmed", "supports", "consistent with",
     *       "within tolerance", "verified", "agrees with".</li>
     *   <li><b>OUTSIDE</b>: "ruled out", "refuted", "rejected",
     *       "violates", "exceeds the bound", "outside tolerance",
     *       "contradicts".</li>
     * </ul>
     *
     * <p>Ambiguous verbs ("indicates", "suggests", "observed") are
     * deliberately excluded — they're often interpretive narration that
     * doesn't commit to a polarity.
     */
    private static final Pattern CONCLUSION_WITHIN = Pattern.compile(
            "(?i)\\b(?:hypothesis\\s+confirmed|confirms?\\s+the\\s+hypothesis|"
                    + "supports?\\s+the\\s+hypothesis|consistent\\s+with\\s+the\\s+hypothesis|"
                    + "within\\s+(?:the\\s+)?(?:tolerance|bound|expected|predicted)|"
                    + "verified|agrees\\s+with\\s+the\\s+bound)\\b");

    private static final Pattern CONCLUSION_OUTSIDE = Pattern.compile(
            "(?i)\\b(?:rules?\\s+out|ruled\\s+out|refute[sd]?|rejected?\\s+the\\s+hypothesis|"
                    + "violates?\\s+(?:the\\s+)?(?:hypothesis|bound)|"
                    + "exceeds?\\s+(?:the\\s+)?bound|"
                    + "outside\\s+(?:the\\s+)?(?:tolerance|bound)|"
                    + "contradicts?\\s+(?:the\\s+)?hypothesis)\\b");

    /**
     * Returns a single-entry reason list when the Conclusion's polarity
     * contradicts the Findings actual vs Hypothesis bound; empty list
     * otherwise. Returns empty on any ambiguity:
     *
     * <ul>
     *   <li>Hypothesis has no parseable bound</li>
     *   <li>Findings has no parseable deviation</li>
     *   <li>Conclusion has no polarity word OR has both (mixed signal)</li>
     * </ul>
     */
    public static List<String> validate(String hypothesis, String findings,
                                         String conclusion) {
        if (hypothesis == null || findings == null || conclusion == null)
            return List.of();

        Matcher hypMatcher = HYPOTHESIS_BOUND.matcher(hypothesis);
        if (!hypMatcher.find()) return List.of();
        double bound;
        try {
            bound = Double.parseDouble(hypMatcher.group(1));
        } catch (NumberFormatException e) { return List.of(); }

        Matcher findMatcher = FINDINGS_DEVIATION.matcher(findings);
        if (!findMatcher.find()) return List.of();
        String capturedActual = findMatcher.group(1) != null
                ? findMatcher.group(1) : findMatcher.group(2);
        double actual;
        try {
            actual = Double.parseDouble(capturedActual);
        } catch (NumberFormatException e) { return List.of(); }

        boolean withinClaim  = CONCLUSION_WITHIN.matcher(conclusion).find();
        boolean outsideClaim = CONCLUSION_OUTSIDE.matcher(conclusion).find();
        if (withinClaim == outsideClaim) return List.of();   // none or both → ambiguous

        boolean actuallyWithin = Math.abs(actual) <= Math.abs(bound);
        boolean expectedWithin = withinClaim;
        if (actuallyWithin == expectedWithin) return List.of();

        String actualPolarity   = actuallyWithin  ? "within"  : "outside";
        String claimedPolarity  = expectedWithin  ? "within"  : "outside";
        return List.of(String.format(
                "Conclusion polarity '%s' contradicts Findings %s%% vs Hypothesis "
                        + "bound %s%% (actually %s; Phase 14 F4).",
                claimedPolarity, actual, bound, actualPolarity));
    }

    /**
     * Structured-findings overload. The polarity check operates on the
     * concatenation of every block's prose.
     */
    public static List<String> validate(String hypothesis,
                                         Map<Integer, String> findingsMap,
                                         String conclusion) {
        if (findingsMap == null || findingsMap.isEmpty()) return List.of();
        return validate(hypothesis,
                MagnitudeRangeValidator.joinBlocks(findingsMap), conclusion);
    }
}
