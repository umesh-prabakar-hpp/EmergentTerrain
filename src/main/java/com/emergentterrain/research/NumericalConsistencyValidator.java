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
     * Phase 12 D2d — a salient INTEGER is one that carries a physical unit
     * marker in the surrounding text. P-a328-417's three load-bearing prose
     * values (2377 MPa, σ₀=97 MPa, k=510 MPa·µm^½) are all integers and slip
     * past {@link #SALIENT_NUMBER} because that regex demands ≥2 fractional
     * digits or scientific notation. The unit-anchored regex below catches
     * them while still excluding casual counts ("2 of 3 reviewers",
     * "approximately 100"). Group 1 is the numeric token (with optional
     * thousands-comma stripped by the caller); group 2 is the unit.
     *
     * <p>Unit set covers the May 2026 corpus: SI mechanical (MPa, GPa, Pa,
     * kJ/mol), thermal (K, °C), spectroscopy (nm, µm, mm, Hz, kHz, MHz, eV),
     * dimensional (m, s, kg). Greek symbols (µ, σ, λ) appear as part of
     * compound units, not bare unit markers.
     */
    private static final Pattern SALIENT_INTEGER_WITH_UNIT = Pattern.compile(
            "(?<![\\d.])(-?[0-9]{1,3}(?:,[0-9]{3})*|-?[0-9]+)"
                    + "\\s*"
                    + "(MPa·µm\\^½|MPa·µm\\^1/2|MPa|GPa|Pa|kJ/mol|kJ|J/mol|K|°C|°|"
                    + "nm|µm|um|mm|cm|km|"
                    + "Hz|kHz|MHz|GHz|eV|keV|MeV|"
                    + "m/s|kg|kg/m\\^3|s|rpm|%)"
                    + "(?![A-Za-z])");

    /**
     * Phase 12 D2b — every {@code [call_N]} marker the LLM emits in prose. The
     * harness assigns these IDs in {@link com.emergentterrain.service.GroqPool}'s
     * tool loop (Phase 11 C1), monotonic across the trace. Findings prose that
     * cites a marker whose underlying tool execution failed is what P-a328-428
     * looks like — the prose used {@code [call_1]} for the headline number but
     * {@code call_1} errored, leaving the value unsourced in spite of the
     * confident citation.
     */
    private static final Pattern CALL_MARKER = Pattern.compile("\\[call_(\\d+)\\]");

    /**
     * Phase 12 D2b — markers in a tool {@code resultJson} that mean the
     * execution failed. The sandbox safety review emits {@code "rejected"} on
     * grammar violations; runtime failures bubble up as {@code "error"} from
     * {@code Tool error: …} in {@link com.emergentterrain.service.GroqPool}'s
     * exception handler (line ~533). Case-insensitive; the check fires when
     * any of these tokens appears as a JSON key or as a phrase in the
     * stringified body.
     *
     * <p>Phase 16 — extended to cover the failure-text variants observed in
     * refresh #3 of {@code research/a3289005/} that the original set missed.
     * Affected papers: P-a328-519 (4 of 5 sandbox calls returned "Invalid
     * range: must be ≥1 and span ≤ 5000" yet were cited as evidence),
     * P-a328-532 (same "Invalid range" pattern, plus a fifth call that
     * failed with "Cannot use array 'result' as scalar"), P-a328-539 (calls 3-6
     * each failed with "REJECTED: The code calculates..." which the validator
     * now needs to treat the same as REJECTED-by-safety), P-a328-543 (call 2
     * sandbox parse-failure). The general pattern is short status prefixes
     * that the sandbox/runtime emits in plain text rather than JSON shape.
     */
    private static final Pattern ERRORED_RESULT_MARKER = Pattern.compile(
            "(?i)\"(?:error|rejected|status)\"\\s*:\\s*\"?(?:true|rejected|error|fail)"
            + "|tool\\s+error\\s*:"
            + "|index\\s+out\\s+of\\s+bounds"
            + "|cannot\\s+use\\s+array\\s+(?:'[^']+'\\s+)?as\\s+scalar"
            + "|REJECTED\\s+by\\s+safety"
            + "|REJECTED\\s*\\(static\\)\\s*:"
            + "|REJECTED\\s*:\\s*"
            + "|Invalid\\s+range\\s*:"
            + "|Invalid\\s+params\\s*:"
            + "|Sandbox(?:ed)?\\s+execution\\s+failed"
            + "|Symbolic\\s+evaluation\\s+failed"
            + "|Topic\\s+not\\s+computable");

    /**
     * Phase 12 D2e — high-deviation marker in a tool result. P-a328-405 /
     * P-a328-422 both had their recovered ε value sit in the trace within
     * tolerance of the prose value, so Check C's literal-numeric match passes
     * — but the trace ALSO carried {@code "deviation": 99.9} on the same
     * record. A recovery with 99.9% deviation against its own cited baseline
     * is unreliable evidence; Check G flags the call regardless of literal
     * agreement.
     *
     * <p>Captured group 1 is the deviation magnitude (decimal or scientific).
     * The validator parses it and compares against {@link #HIGH_DEVIATION_THRESHOLD}.
     */
    private static final Pattern DEVIATION_FIELD = Pattern.compile(
            "(?i)\"(?:deviation|error_pct|max_dev|max_deviation)\"\\s*:\\s*"
            + "(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    /** Phase 12 D2e — magnitude above which a deviation is treated as unreliable evidence. */
    private static final double HIGH_DEVIATION_THRESHOLD = 50.0;

    /**
     * Phase 16 — common "framing" percentage values the LLM cites as targets
     * or thresholds rather than computed quantities. P-a328-526 cited "50 %"
     * as an efficiency-target framing reference; the existing D2d check
     * flagged it as a fabricated derivation. These values are textbook
     * round-number markers and warrant a softer severity than
     * "suspected derived-formula divergence." Anything outside this set falls
     * back to the standard derivation-flag severity.
     */
    private static final java.util.Set<Double> COMMON_FRAMING_PERCENTAGES = java.util.Set.of(
            5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 33.0, 40.0, 50.0,
            60.0, 67.0, 70.0, 75.0, 80.0, 90.0, 95.0, 99.0, 100.0);

    /**
     * Phase 16 — confidence-interval pattern. Matches {@code X ± Y} where X is
     * the central value and Y is the half-width. Accepts the Unicode ± plus the
     * ASCII {@code +/-} and {@code +-} variants the LLM sometimes emits. Y can
     * be in scientific notation; X is restricted to plain decimal so the
     * pattern doesn't fire on exponent fragments like "1.2e-3".
     *
     * <p>Group 1 = central value X, group 2 = half-width Y.
     */
    private static final Pattern CI_PATTERN = Pattern.compile(
            "([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*(?:±|\\+/-|\\+-)\\s*"
                    + "([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    /**
     * Phase 16 — slack factor for CI matching. A prose half-width must be at
     * least {@code traceHalfWidth × CI_SHRINKAGE_SLACK} to pass. P-a328-528
     * narrowed 0.07 → 0.02 (29% of trace) — well below this floor. Tight
     * rounding ("0.07" → "0.06") would be 86% of trace and passes; "0.07" →
     * "0.04" is 57% and passes; "0.07" → "0.02" is 29% and fails.
     *
     * <p>0.5 chosen because halving an uncertainty band is a strong fabrication
     * signal: it doubles the apparent significance of any deviation from
     * baseline. Numbers below this are not within the range that rounding,
     * sample-resampling, or statistical model swaps would explain.
     */
    private static final double CI_SHRINKAGE_SLACK = 0.5;

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
        String findings = MagnitudeRangeValidator.joinBlocks(findingsMap);

        // Phase 12 D2c — when the trace was instantiated but stayed EMPTY
        // (professor stage ran and errored before any tool call landed) AND
        // findings cite at least one salient numeric, flag a single catch-all
        // reason. P-a328-419 hit this exact shape: prof stage threw, profTrace
        // was an empty list, paper fell through firstPass with σ_fail values
        // in prose anchored to nothing.
        //
        // {@code null} trace is treated as "professor stage was never attempted"
        // (JSON-path / Ollama-fallback / legacy callers that pre-date F5.5).
        // For those callers the seed-engine numbers are the prose's source and
        // the validator has no visibility — skip the check to preserve the
        // F5.5 short-circuit semantics those paths rely on.
        if (toolCallTrace == null) return reasons;
        if (toolCallTrace.isEmpty()) {
            findUnanchoredFindingsWithoutTrace(findings, reasons);
            return reasons;
        }

        findUnsourcedFindingsNumbers(hypothesis, findings, conclusion, toolCallTrace, reasons);
        // Phase 12 D2b — call markers in findings prose that resolve to a
        // failed / rejected tool execution. Caught after Check C so reviewers
        // see the literal-mismatch reason first, then the cite-of-broken-tool
        // reason as a separate entry.
        findCitationsToErroredCalls(findings, toolCallTrace, reasons);
        // Phase 12 D2e — calls whose result body carries a high "deviation" /
        // "error_pct" field. Independent of literal-numeric agreement; a tool
        // result with 99.9% deviation against its own cited baseline is
        // unreliable evidence even when the prose's numeric matches.
        findHighDeviationCalls(toolCallTrace, reasons);
        // Phase 16 — one-sided CI tightening. Prose "0.34 ± 0.02" against a
        // trace "0.34 ± 0.07" is a fabrication direction; widening uncertainty
        // is fine. P-a328-528 / P-a328-521 pattern.
        findCiShrinkage(findings, toolCallTrace, reasons);
        // Phase 16 — Hypothesis-vs-executed-range scope overreach. Prose
        // claims results over a range never executed by any tool call.
        // P-a328-547 pattern.
        findScopeOverreach(hypothesis, findings, toolCallTrace, reasons);
        // Phase 16 — inline "Computation N" stub embedded in findings prose
        // beyond what F1b's block-count gate catches. P-a328-553 pattern.
        findInlineComputationStubs(findings, toolCallTrace, reasons);
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
        // Phase 12 D2d — unit-anchored integers (e.g. "2,377 MPa", "97 MPa")
        // that are absent from any source. Catches the Hall-Petch P-a328-417
        // pattern: prose σ_y,max = 2377 MPa derived from prose-stated σ₀, k, d
        // but the trace returned σ_y,max = 149 MPa. The derived value sits 16×
        // off the trace and is not in the hypothesis or conclusion either.
        Matcher u = SALIENT_INTEGER_WITH_UNIT.matcher(findings);
        java.util.Set<String> alreadyFlaggedUnit = new java.util.HashSet<>();
        while (u.find()) {
            String numTok = u.group(1).replace(",", "");
            String unit   = u.group(2);
            double n;
            try { n = Double.parseDouble(numTok); }
            catch (NumberFormatException ex) { continue; }
            // Skip 0 and 1 — common in unit-bearing context as references
            // ("1 K above baseline", "0 MPa baseline").
            if (n == 0.0 || n == 1.0) continue;
            String key = numTok + " " + unit;
            if (!alreadyFlaggedUnit.add(key)) continue;
            if (!isPermittedNumber(n, permitted)) {
                // Phase 16 — softer severity for "% framing" values like
                // "50 %", "75 %", "100 %". P-a328-526 cited "50 %" as a target
                // framing reference for Carnot-efficiency discussion; the
                // standard derivation-divergence severity over-reads such a
                // round-number reference as a fabricated calculation.
                if ("%".equals(unit) && COMMON_FRAMING_PERCENTAGES.contains(n)) {
                    reasons.add(String.format(
                            "Findings cites '%s %%' as an unsourced framing value — round-number target "
                                    + "not derived from any hypothesis, conclusion, or tool-call source.",
                            numTok));
                    continue;
                }
                reasons.add(String.format(
                        "Findings cites unit-anchored value '%s %s' that is not within ±1%% of any "
                                + "number in the hypothesis, conclusion, or tool-call trace results "
                                + "(suspected derived-formula divergence).",
                        numTok, unit));
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

    /**
     * Phase 7 F5.5 / Phase 16 — match a prose-cited value against the permitted
     * source set within ±1%. Phase 16 adds a *magnitude* fallback: when the
     * prose number doesn't match a signed source value, also try the absolute
     * value of the source. Rationale (P-a328-555): tool result strings often
     * carry signed numbers like "deviation -15.286" while the prose cites the
     * magnitude "15.286" — these refer to the same quantity. The check was
     * fabrication-flagging the unsigned prose form despite the trace
     * containing the signed source. Magnitude-only fallback closes that gap
     * without making the validator permissive in other directions, because:
     * (a) the strict sign-matching branch runs first, so signed-vs-signed
     * comparisons still catch real polarity disagreements; (b) the magnitude
     * branch is symmetric, so a prose claim of "5" matches a trace "-5" or
     * "5", which is the prose-as-magnitude semantics the LLM intends.
     */
    private static boolean isPermittedNumber(double value, List<Double> permitted) {
        for (double p : permitted) {
            if (p == 0.0) {
                if (value == 0.0) return true;
                continue;
            }
            if (Math.abs(value - p) / Math.abs(p) <= F5_5_RELATIVE_TOLERANCE) return true;
            // Phase 16 — magnitude fallback. Match prose unsigned-magnitude
            // citations against signed trace values (e.g. prose "15.286" vs
            // trace "-15.286").
            if (Math.abs(value - Math.abs(p)) / Math.abs(p) <= F5_5_RELATIVE_TOLERANCE) return true;
        }
        return false;
    }

    /**
     * Phase 12 D2c — fire when the trace is empty (null or zero entries) AND
     * findings prose contains at least one salient numeric. The reason text
     * names a count rather than specific values so the F5.5 short-circuit test
     * (which asserts on specific value tokens like {@code 42.30}) still passes.
     * Empty-findings short-circuit so JSON-path papers that legitimately have
     * no numerics in their first-pass prose aren't flagged.
     */
    private static void findUnanchoredFindingsWithoutTrace(String findings, List<String> reasons) {
        if (findings == null || findings.isBlank()) return;
        int salient = 0;
        Matcher m = SALIENT_NUMBER.matcher(findings);
        while (m.find()) salient++;
        // Phase 12 D2d composability — unit-anchored integers also count.
        // P-a328-419 cited σ_fail values as plain integers with MPa units; the
        // salient-decimal regex alone would miss them.
        Matcher u = SALIENT_INTEGER_WITH_UNIT.matcher(findings);
        while (u.find()) salient++;
        if (salient == 0) return;
        reasons.add(String.format(
                "Trace is empty; findings cite %d salient numeric value%s with no Methods anchor "
                        + "(no tool call ran or the professor stage errored before entering the tool loop).",
                salient, salient == 1 ? "" : "s"));
    }

    /**
     * Phase 12 D2b — every {@code [call_N]} marker the LLM emits in prose
     * must resolve to a tool execution whose {@code resultJson} is not an
     * error / rejection body. Markers that reference IDs absent from the
     * trace AND markers that reference an errored call both flag.
     */
    private static void findCitationsToErroredCalls(
            String findings,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (findings == null || findings.isBlank()) return;
        if (trace == null || trace.isEmpty()) return;
        // Build a map from call_N → ToolInvocation. Phase 11 C1 assigns
        // monotonic IDs so the trace is keyed by callId directly; pre-Phase-11
        // ToolInvocations (callId=null) get a synthetic 1-based index, mirroring
        // how GroqPool stamps them today.
        java.util.Map<String, com.emergentterrain.llm.ToolInvocation> byId = new java.util.HashMap<>();
        int idx = 1;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null) { idx++; continue; }
            String id = inv.callId() != null ? inv.callId() : ("call_" + idx);
            byId.put(id, inv);
            idx++;
        }
        Matcher m = CALL_MARKER.matcher(findings);
        java.util.Set<String> alreadyFlagged = new java.util.HashSet<>();
        while (m.find()) {
            String callId = "call_" + m.group(1);
            if (!alreadyFlagged.add(callId)) continue;
            com.emergentterrain.llm.ToolInvocation inv = byId.get(callId);
            if (inv == null) {
                // Phase 16 — [call_0] labelling-drift heuristic. The harness
                // assigns 1-based callIds (Phase 11 C1, "call_1" is the first
                // entry). P-a328-516 emitted prose citations to [call_0]; the
                // trace contained calls 1-6. The fabrication-pattern severity
                // ("does not appear in trace") over-reads the prose's intent —
                // the LLM almost certainly meant call_1 and slipped a zero-
                // indexed citation in. Emit a softer reason that names the
                // labelling drift, when AND only when a "call_1" exists.
                if ("call_0".equals(callId) && byId.containsKey("call_1")) {
                    reasons.add(
                            "Findings cites '[call_0]' which appears to be a zero-indexed labelling drift "
                                    + "— call_1 is the first call in the trace (callIds are 1-based per Phase "
                                    + "11 C1). Renumber to call_1 or strip the citation.");
                    continue;
                }
                reasons.add(String.format(
                        "Findings cites '[%s]' which does not appear in the persisted tool-call trace "
                                + "(only %d call%s in trace).",
                        callId, trace.size(), trace.size() == 1 ? "" : "s"));
                continue;
            }
            String body = inv.resultJson();
            if (body == null || body.isBlank()) {
                reasons.add(String.format(
                        "Findings cites '[%s]' but its tool result is empty — no numerics to anchor the claim.",
                        callId));
                continue;
            }
            if (ERRORED_RESULT_MARKER.matcher(body).find()) {
                reasons.add(String.format(
                        "Findings cites '[%s]' whose tool execution errored or was rejected by safety "
                                + "review — numerics tied to this marker are unsourced.",
                        callId));
            }
        }
    }

    /**
     * Phase 12 D2e — flag any tool result whose body carries a {@code deviation}
     * or {@code error_pct} field above {@link #HIGH_DEVIATION_THRESHOLD}.
     * Independent of literal-numeric agreement; the recovery is unreliable
     * regardless of whether prose cites the value within tolerance.
     */
    private static void findHighDeviationCalls(
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (trace == null || trace.isEmpty()) return;
        int idx = 1;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null || inv.resultJson() == null) { idx++; continue; }
            String callId = inv.callId() != null ? inv.callId() : ("call_" + idx);
            idx++;
            Matcher m = DEVIATION_FIELD.matcher(inv.resultJson());
            while (m.find()) {
                try {
                    double dev = Math.abs(Double.parseDouble(m.group(1)));
                    if (dev > HIGH_DEVIATION_THRESHOLD) {
                        reasons.add(String.format(
                                "[%s] reports deviation %.2f%% against its cited baseline — above the "
                                        + "%.0f%% reliability threshold; recovery is unreliable evidence.",
                                callId, dev, HIGH_DEVIATION_THRESHOLD));
                        break;  // one reason per call
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /**
     * Phase 16 — flag prose "X ± Y" pairs whose half-width Y is materially
     * tighter than the matching trace half-width. Matching is by central
     * value within ±1% (the same tolerance F5.5 uses for value matching).
     * If no matching central value exists in the trace, the prose pair is
     * skipped (we cannot pair an orphan prose CI against a missing trace
     * counterpart without false-positives).
     *
     * <p>Sole direction flagged is *tightening* — the prose claims a narrower
     * CI than the trace. Widening (prose Y > trace Y) is treated as a
     * conservative restatement and never flagged.
     */
    private static void findCiShrinkage(
            String findings,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (findings == null || findings.isBlank()) return;
        if (trace == null || trace.isEmpty()) return;

        // Collect (central, halfWidth) pairs from every trace result.
        List<double[]> tracePairs = new ArrayList<>();
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null || inv.resultJson() == null) continue;
            Matcher tm = CI_PATTERN.matcher(inv.resultJson());
            while (tm.find()) {
                try {
                    double x = Double.parseDouble(tm.group(1));
                    double y = Double.parseDouble(tm.group(2));
                    if (y > 0) tracePairs.add(new double[]{x, y});
                } catch (NumberFormatException ignored) {}
            }
        }
        if (tracePairs.isEmpty()) return;

        Matcher pm = CI_PATTERN.matcher(findings);
        java.util.Set<String> alreadyFlagged = new java.util.HashSet<>();
        while (pm.find()) {
            double proseX;
            double proseY;
            try {
                proseX = Double.parseDouble(pm.group(1));
                proseY = Double.parseDouble(pm.group(2));
            } catch (NumberFormatException ex) { continue; }
            if (proseY <= 0) continue;

            // Find the closest trace central X to the prose X (within ±1%
            // F5.5 tolerance). If none, skip — no pairing means no inference.
            double bestY = -1;
            for (double[] pair : tracePairs) {
                double tx = pair[0];
                double ty = pair[1];
                if (tx == 0) {
                    if (proseX != 0) continue;
                } else if (Math.abs(proseX - tx) / Math.abs(tx) > F5_5_RELATIVE_TOLERANCE) {
                    continue;
                }
                // Accept this pair; pick the LARGEST trace half-width that
                // matches the central value, so the strictest comparison
                // (smallest trace Y) doesn't unfairly favour the prose.
                if (ty > bestY) bestY = ty;
            }
            if (bestY < 0) continue;

            if (proseY < bestY * CI_SHRINKAGE_SLACK) {
                String key = proseX + "_" + proseY + "_" + bestY;
                if (!alreadyFlagged.add(key)) continue;
                reasons.add(String.format(
                        "Findings cites confidence interval '%s ± %s' but the matching tool result "
                                + "reports '± %s' for the same central value — prose half-width is "
                                + "%.0f%% of trace half-width (slack floor: %.0f%%). Tightening "
                                + "uncertainty without statistical justification is a fabrication "
                                + "direction; widening would not flag.",
                        pm.group(1), pm.group(2),
                        formatTrim(bestY),
                        100.0 * proseY / bestY, 100.0 * CI_SHRINKAGE_SLACK));
            }
        }
    }

    /**
     * Phase 16 — flag prose range claims that aren't covered by any executed
     * tool call's range. Range parsing is intentionally conservative — we only
     * pick up the unambiguous {@code [a, b)} and {@code [a, b]} bracket forms.
     * Natural-language ranges ("between X and Y", "from X to Y") are deferred
     * because the false-positive surface is wider (those phrasings also appear
     * in chemistry-style "from 25 to 30 °C" measurements, etc.).
     *
     * <p>An executed range is extracted from each {@link
     * com.emergentterrain.llm.ToolInvocation#argsJson} by parsing
     * {@code range_start}/{@code range_end} or {@code start}/{@code end} pairs.
     * The union of those intervals is the executed-range set.
     */
    private static void findScopeOverreach(
            String hypothesis, String findings,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (trace == null || trace.isEmpty()) return;

        // Collect executed ranges from the trace.
        List<double[]> executed = new ArrayList<>();
        Pattern rangeArgs = Pattern.compile(
                "(?i)\"(?:range_start|start)\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?"
                        + "[^}]*?"
                        + "\"(?:range_end|end)\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?");
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null || inv.argsJson() == null) continue;
            Matcher am = rangeArgs.matcher(inv.argsJson());
            while (am.find()) {
                try {
                    double a = Double.parseDouble(am.group(1));
                    double b = Double.parseDouble(am.group(2));
                    if (b > a) executed.add(new double[]{a, b});
                } catch (NumberFormatException ignored) {}
            }
        }
        if (executed.isEmpty()) return;

        // Parse "[a, b)" or "[a, b]" from hypothesis + findings.
        Pattern bracketRange = Pattern.compile(
                "\\[\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*[\\)\\]]");
        String combined = (hypothesis != null ? hypothesis : "") + "\n"
                + (findings != null ? findings : "");
        Matcher pm = bracketRange.matcher(combined);
        java.util.Set<String> alreadyFlagged = new java.util.HashSet<>();
        while (pm.find()) {
            double a;
            double b;
            try {
                a = Double.parseDouble(pm.group(1));
                b = Double.parseDouble(pm.group(2));
            } catch (NumberFormatException ex) { continue; }
            if (b <= a) continue;

            // Pass when SOME executed range covers [a, b] within ±1% on each
            // edge. Slack matches F5_5_RELATIVE_TOLERANCE so a prose
            // "[326, 466)" is allowed to ride a trace "[325, 467)" etc.
            boolean covered = false;
            for (double[] ex : executed) {
                double slackA = Math.max(Math.abs(ex[0]), 1.0) * F5_5_RELATIVE_TOLERANCE;
                double slackB = Math.max(Math.abs(ex[1]), 1.0) * F5_5_RELATIVE_TOLERANCE;
                if (a >= ex[0] - slackA && b <= ex[1] + slackB) {
                    covered = true;
                    break;
                }
            }
            if (covered) continue;

            String key = a + "_" + b;
            if (!alreadyFlagged.add(key)) continue;
            reasons.add(String.format(
                    "Prose claims results over range [%s, %s) but no tool call executed against this "
                            + "interval — the executed-range union does not cover it (scope overreach).",
                    formatTrim(a), formatTrim(b)));
        }
    }

    /**
     * Phase 16 — count {@code Computation N —} mentions in the findings prose
     * and flag when the total exceeds the number of successful tool calls.
     * Complementary to F1b's {@code block-count-guard}: F1b counts *block
     * headers* (separate markdown blocks), while this check counts *every*
     * occurrence of the marker including ones embedded mid-paragraph.
     * P-a328-553 emitted "Computation 3 — Testing a scale closer to the
     * starting point..." inside a Findings paragraph; F1b did not increment
     * its block counter so the gate let it through.
     */
    private static void findInlineComputationStubs(
            String findings,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
            List<String> reasons) {
        if (findings == null || findings.isBlank()) return;
        Pattern compMarker = Pattern.compile("\\bComputation\\s+([0-9]+)\\s+(?:—|-)");
        Matcher m = compMarker.matcher(findings);
        java.util.Set<Integer> mentioned = new java.util.TreeSet<>();
        while (m.find()) {
            try { mentioned.add(Integer.parseInt(m.group(1))); }
            catch (NumberFormatException ignored) {}
        }
        if (mentioned.isEmpty()) return;
        int successes = countSuccessfulCalls(trace);
        int highest = mentioned.stream().max(Integer::compare).orElse(0);
        if (highest <= Math.max(1, successes)) return;
        // Build a sorted list of the mentioned IDs that exceed the success
        // count for the reason text.
        List<Integer> overshoot = new ArrayList<>();
        for (int id : mentioned) {
            if (id > successes) overshoot.add(id);
        }
        reasons.add(String.format(
                "Findings prose mentions Computation %s but the trace contains only %d successful "
                        + "tool call%s — inline computation-stub fabrication (per-paragraph stub "
                        + "form that F1b's block-count gate does not catch).",
                overshoot, successes, successes == 1 ? "" : "s"));
    }

    /**
     * Count tool invocations whose result is non-empty and does not match an
     * errored-result marker. Shared between F1b-companion checks and any
     * future caller that needs a "this many calls actually succeeded" view of
     * the trace.
     */
    private static int countSuccessfulCalls(
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (trace == null) return 0;
        int n = 0;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null) continue;
            String body = inv.resultJson();
            if (body == null || body.isBlank()) continue;
            if (ERRORED_RESULT_MARKER.matcher(body).find()) continue;
            n++;
        }
        return n;
    }

    /** Trim a trailing {@code .0} when the value is integral, otherwise format the double normally. */
    private static String formatTrim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
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
