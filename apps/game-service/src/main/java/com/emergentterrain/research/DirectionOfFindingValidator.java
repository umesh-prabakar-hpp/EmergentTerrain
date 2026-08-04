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
     * Conclusion stamps the result as a null / untested outcome. Distinct
     * from CONCLUSION_OUTSIDE (which is a refutation claim): null means the
     * narrator says "no result was obtained", typically the validator-stub
     * phrasing or "remains unverified by direct computational evidence".
     * P-a328-883 carried this against a Findings section that cited
     * bestAngle=27.0° from call_2 — a concrete positive verification.
     */
    // 2026-07-06 (P-b72c-77627802) — narrowed: the old alternation treated LIMITATION vocabulary
    // ("an intermediate scale … remains untested") as a null VERDICT and demoted a paper whose
    // conclusion was a positive confirmation. Doubly wrong because the question harvester (phase-33
    // T1) actively rewards "remains untested" phrasing in limitations. A null stamp must either be
    // the explicit phrase or a negation whose SUBJECT is the paper's claim
    // (hypothesis/prediction/claim/conjecture) — bare "remains untested"/"not verified" about a
    // scale, window, or parameter is honest scope disclosure, not a verdict.
    private static final Pattern CONCLUSION_NULL = Pattern.compile(
            "(?i)\\b(?:null\\s+result|"
                    + "no\\s+(?:direct\\s+)?computational\\s+evidence|"
                    + "pre[-\\u2010\\u2011]registered\\s+prediction\\s+(?:was\\s+)?not\\s+verified|"
                    + "(?:hypothesis|prediction|claim|conjecture)\\s+(?:was|were|is|are)?\\s*"
                    + "(?:not|never)\\s+(?:verified|supported|confirmed)|"
                    + "(?:hypothesis|prediction|claim|conjecture)\\s+remains?\\s+(?:untested|unverified)|"
                    + "recorded\\s+as\\s+(?:a\\s+)?null\\s+result)\\b");

    /**
     * Findings prose attributes a concrete numeric value to a specific tool
     * call — the smoking gun for "the computation ran and produced X". When
     * this is present alongside a CONCLUSION_NULL stamp AND the trace is
     * non-empty, the paper is internally contradictory.
     */
    private static final Pattern FINDINGS_CITES_TRACE_NUMERIC = Pattern.compile(
            "(?i)(?:\\[call_\\d+\\]|from\\s+call_?\\d+|tool[-\\s]call\\s*#?\\d+|"
                    + "call_\\d+\\s+(?:returned|computed|produced|gives?|gave|yields?))"
                    + ".{0,80}?[0-9]+(?:\\.[0-9]+)?",
            Pattern.DOTALL);

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

    /**
     * Three-way overload — extends the polarity check with a "Findings has
     * concrete result + Conclusion stamps null" detection. The trace size
     * gates the new check: an honestly-empty trace ({@code traceSize == 0})
     * skips the new check (Conclusion=null is correct when no computation
     * ran), while a non-empty trace with Findings citing a trace numeric
     * and Conclusion stamping null triggers the contradiction.
     *
     * <p>Target case: P-a328-883. Findings cites
     * {@code "verified as 27.0°, yielding a trajectory range of 34.66 m"}
     * (call_2 returned exactly those values), Conclusion stamps
     * {@code "recorded as a null result"}. The two-arg overload missed
     * this because the Hypothesis used a directional comparator
     * ({@code "less than 45°"}) rather than a tolerance, so
     * HYPOTHESIS_BOUND didn't match.
     */
    public static List<String> validate(String hypothesis,
                                         Map<Integer, String> findingsMap,
                                         String conclusion,
                                         int traceSize) {
        List<String> reasons = new java.util.ArrayList<>(
                validate(hypothesis, findingsMap, conclusion));
        if (traceSize > 0 && findingsMap != null && !findingsMap.isEmpty()
                && conclusion != null) {
            String joined = MagnitudeRangeValidator.joinBlocks(findingsMap);
            boolean conclNull = CONCLUSION_NULL.matcher(conclusion).find();
            boolean findingsCitesTrace =
                    FINDINGS_CITES_TRACE_NUMERIC.matcher(joined).find();
            if (conclNull && findingsCitesTrace) {
                reasons.add("Conclusion stamps null-result but Findings cites "
                        + "concrete numerics from a non-empty trace (size="
                        + traceSize + "; Phase 14 F4-ext, 2026-05-21).");
            }
        }
        return reasons;
    }
}
