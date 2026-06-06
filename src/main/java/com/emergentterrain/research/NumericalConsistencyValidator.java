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
                    + "((?:[0-9]{3,}|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?)");

    /**
     * Any bare numeric token. Used to scan nearby actuals. Accepts thousands-grouped
     * integers ({@code 228,000}) as one token (the grouped alternative precedes the plain
     * one) so a coordinate like "228,000" isn't fragmented into 228/000; strip the
     * separators with {@code .replace(",", "")} before parsing the captured group.
     */
    private static final Pattern NUMERIC_TOKEN =
            Pattern.compile("\\b((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)");

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
     * 2026-05-28 rec #1 (research/a3289005) — maximum power-of-ten gap tolerated
     * by the scale-invariant fallback in {@link #isPermittedNumber}. Ported from
     * {@link CitationValidator#MAX_SCALE_DECADES}; covers scaling a Planck-scale
     * quantity (~1e-34) up to O(1) for readability. Gaps beyond this are treated
     * as coincidental mantissa collisions and not reconciled.
     */
    private static final int MAX_SCALE_DECADES = 40;

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
     * Does a tool-call result body carry an errored / safety-rejected marker?
     * The single source of truth for "this call did NOT return a usable result"
     * — used by the F1b-companion checks here AND by the revision-prompt trace
     * renderer, which must label failed calls DO-NOT-CITE so the reviser does
     * not cite an errored call as data (P-a328-994 cited the errored call_1/5).
     *
     * @param resultJson the raw {@link com.emergentterrain.llm.ToolInvocation#resultJson()}
     * @return true when the body is non-blank AND matches an errored-result marker
     */
    public static boolean isErroredResult(String resultJson) {
        return resultJson != null && !resultJson.isBlank()
                && ERRORED_RESULT_MARKER.matcher(resultJson).find();
    }

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
        // No trace on this overload → no tool-parameter grounding (empty set).
        findCloseToFarFromObserved(findings, conclusion, reasons, java.util.Set.of());

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
        String findings = MagnitudeRangeValidator.joinBlocks(findingsMap);
        // Base checks (A + B). Unlike the trace-less overload, Check A here grounds
        // "close to N" anchors against the numbers the model actually passed as tool
        // arguments (window_start / window_size / num_windows / range bounds /
        // baseline). A round value like 10000 that is a window-size *parameter* is a
        // grounded reference, not a fabricated result — P-b72c-6 was false-NULLed for
        // "close to 10000" with no Findings value near it (Check B needs no grounding).
        List<String> reasons = new ArrayList<>();
        if (hypothesis != null && findings != null && conclusion != null) {
            findCloseToFarFromObserved(findings, conclusion, reasons,
                    collectToolParamNumbers(toolCallTrace));
            findRangeMismatch(hypothesis, conclusion, reasons);
        }

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
                                                    List<String> reasons,
                                                    java.util.Set<Double> groundedParams) {
        Matcher m = COMPARISON_ANCHOR.matcher(conclusion);
        while (m.find()) {
            double anchor = Double.parseDouble(m.group(1));
            if (anchor == 0) continue;
            String unit = m.group(2);
            // 2026-05-28 rec #1 — percent↔fraction normalisation. When the
            // anchor carries a "%" unit the prose value is a percentage (e.g.
            // "close to 2.8%") while the Findings frequently report the same
            // quantity as a bare fraction (0.023). P-a328-996 was a false NULL
            // because 0.023 sits far from 2.8 yet within ±20% of 2.8/100=0.028.
            // Test the ±20% band against both the percent and the fraction form.
            double[] candidateAnchors = "%".equals(unit)
                    ? new double[]{anchor, anchor / 100.0}
                    : new double[]{anchor};
            // Look for any number in Findings within 20% of the anchor — same unit
            // suffix preferred but not required.
            boolean foundNearby = false;
            Matcher tokens = NUMERIC_TOKEN.matcher(findings);
            scan:
            while (tokens.find()) {
                double observed = Double.parseDouble(tokens.group(1).replace(",", ""));
                for (double a : candidateAnchors) {
                    if (Math.abs(observed - a) / Math.abs(a) <= 0.20) {
                        foundNearby = true;
                        break scan;
                    }
                }
            }
            // 2026-05-29 — the anchor is also grounded if it (near-exactly) matches a
            // number the model passed as a tool argument: a window_size / range bound /
            // baseline the run actually used is a parameter reference, not a fabricated
            // result. Tight tolerance (1%, not the 20% result band) so a real result
            // claim that merely sits within 20% of some unrelated parameter is NOT
            // exempted. Fixes the P-b72c-6 false NULL on "close to 10000" (a window
            // size), without weakening the fabrication catch for genuine result claims.
            if (!foundNearby && groundedParams != null) {
                for (double a : candidateAnchors) {
                    for (double p : groundedParams) {
                        if (Math.abs(p - a) <= Math.max(Math.abs(a), 1.0) * F5_5_RELATIVE_TOLERANCE) {
                            foundNearby = true;
                            break;
                        }
                    }
                    if (foundNearby) break;
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
     * JSON value-after-colon numeric, used to harvest the numbers a tool call was
     * invoked with (window_start / window_size / num_windows / range bounds /
     * baseline / k / c). Matches the value side of {@code "key": <num>} so keys and
     * numbers embedded inside string values are not collected.
     */
    private static final Pattern JSON_NUMERIC_VALUE =
            Pattern.compile(":\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?");

    /**
     * 2026-05-29 — the set of numeric values the model passed as tool arguments
     * across the whole trace. {@link #findCloseToFarFromObserved} treats a "close to
     * N" anchor as grounded when N matches one of these (near-exactly), so a window
     * size / range bound / baseline the run actually used is not mistaken for a
     * fabricated result. Null/empty trace → empty set (no grounding).
     */
    private static java.util.Set<Double> collectToolParamNumbers(
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        java.util.Set<Double> out = new java.util.HashSet<>();
        if (trace == null) return out;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null || inv.argsJson() == null) continue;
            Matcher m = JSON_NUMERIC_VALUE.matcher(inv.argsJson());
            while (m.find()) {
                try { out.add(Double.parseDouble(m.group(1))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return out;
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
            double coord = Double.parseDouble(con.group(1).replace(",", ""));
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
        // 2026-06-04 rec #1 — one-step derived-arithmetic sources. The hypothesis's own
        // constants (k, c) and each single tool result's numbers are kept separately so a
        // prose value that is one arithmetic step over them — k·x, or x±y / x·y of two
        // numbers the SAME computation produced — is traceable, not fabricated. The
        // panel routinely asks the revision to decompose a bound into k·rhs_scalar
        // (P-b72c-115/116: 0.344 = 2×0.172, 0.265 = 2×0.1325) or to state a CI band /
        // observed spread (P-b72c-118: 0.006 = 0.023−0.017; P-b72c-120: 0.0046 =
        // 2×(0.7536−0.7513)); the single-number ±1% match was nulling exactly the
        // arithmetic the panel requested. Scope is deliberately tight: coefficients
        // come from the hypothesis only, and pairs must come from one result — global
        // cross-call pairing over hundreds of trace numbers would match almost any
        // value by chance and launder genuine fabrications.
        List<Double> hypothesisCoeffs = new ArrayList<>();
        collectAllNumbers(hypothesis, hypothesisCoeffs);
        List<List<Double>> perResultNumbers = new ArrayList<>();
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv != null && inv.resultJson() != null) {
                collectAllNumbers(inv.resultJson(), permitted);
                List<Double> single = new ArrayList<>();
                collectAllNumbers(inv.resultJson(), single);
                perResultNumbers.add(single);
            }
        }
        if (permitted.isEmpty()) return;

        // 2026-06-05 rec #1 — canonical CI multipliers are methodological constants,
        // not fabricated results, WHEN the trace itself reports confidence intervals.
        // P-b72c-131 was struck for citing 1.96, which is verifiably the multiplier
        // behind the trace's own half-widths (1.96·√(0.025·0.975/400) = 0.0153 ≈ the
        // reported ±0.015). Gated on CI context so a bare 1.96 in a non-CI paper still
        // flags — the allowlist never applies where no interval was computed.
        boolean traceHasCi = traceContainsConfidenceIntervals(trace);

        Matcher m = SALIENT_NUMBER.matcher(findings);
        while (m.find()) {
            double n;
            try { n = Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ex) { continue; }
            if (traceHasCi && isCanonicalCiConstant(n)) continue;
            if (!isPermittedNumber(n, permitted)
                    && !isDerivedOneStep(n, hypothesisCoeffs, perResultNumbers, permitted)) {
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

    /**
     * Phase 32 Wave 1 enforce (32.2) — the value-tracing/scope split. These
     * four reason classes re-derive a prose number against the trace by
     * value-matching; under {@code manifest.validator-mode: enforce} the
     * manifest traceability validator owns that job (4-session shadow A/B:
     * the value arms went 3/3 FP while the manifest went 0 missed
     * fabrications), so UniversityService filters them out. Every other
     * reason class — executed-range union, Conclusion-vs-Findings
     * consistency, call-marker integrity, computation-count — is structural
     * and stays: P-b72c-244's scope catch is the live proof.
     */
    public static boolean isValueTracingReason(String reason) {
        if (reason == null) return false;
        return reason.startsWith("Findings cites salient value")
                || reason.startsWith("Findings cites unit-anchored value")
                || reason.contains("as an unsourced framing value")
                || reason.startsWith("Findings cites confidence interval");
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
            // 2026-05-28 rec #1 — scale-tolerant fallback. A prose value and a
            // source value with identical leading significant digits differing
            // only by a clean power of ten (prose "5.840" vs trace
            // "σ_obs=5.840e-08", P-a328-998) are the same computed quantity
            // recorded at a different unit scale. Mirrors the P0 fix landed in
            // CitationValidator.valueAppearsInResultScaleTolerant; bounded to
            // MAX_SCALE_DECADES so unrelated mantissa coincidences far apart in
            // magnitude are not reconciled. This was the dominant residual
            // false-NULL source in the post-rotation a3289005 batch.
            if (sameMantissaDifferentScale(value, p)) return true;
        }
        return false;
    }

    /**
     * 2026-06-05 rec #1 — the canonical two-sided normal CI multipliers for the
     * 90% / 95% / 99% levels. A narrator explaining how the trace's intervals were
     * constructed legitimately cites these; they are textbook constants that by
     * definition trace to no tool output. Deliberately a tiny closed set — NOT a
     * general "well-known constants" registry, which would erode the fabrication
     * catch one plausible constant at a time.
     */
    private static final double[] CANONICAL_CI_CONSTANTS = {1.645, 1.96, 2.576};

    /** True when {@code value} is one of {@link #CANONICAL_CI_CONSTANTS} (±1%). */
    static boolean isCanonicalCiConstant(double value) {
        for (double c : CANONICAL_CI_CONSTANTS) {
            if (Math.abs(value - c) / c <= F5_5_RELATIVE_TOLERANCE) return true;
        }
        return false;
    }

    /** True when any tool result reports a confidence interval ("% CI"). */
    static boolean traceContainsConfidenceIntervals(
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (trace == null) return false;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv != null && inv.resultJson() != null && inv.resultJson().contains("% CI")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-06-04 rec #1 — true when {@code value} is one arithmetic step away from
     * traceable sources: {@code a·x} for a hypothesis constant {@code a} and any
     * permitted number {@code x}, or {@code x±y} for two numbers from
     * the SAME tool result (optionally scaled by a hypothesis constant). Used only
     * as a fallback after {@link #isPermittedNumber} fails on the unitless salient
     * branch — the unit-anchored branch stays strict because derived-formula
     * divergence (multi-step, wrong by far more than one operation) is exactly what
     * it exists to catch. Coefficients of 0 and 1 are skipped (identity / degenerate).
     */
    static boolean isDerivedOneStep(double value, List<Double> hypothesisCoeffs,
                                    List<List<Double>> perResultNumbers,
                                    List<Double> permitted) {
        // a·x — hypothesis coefficient times any permitted source number. STRICT
        // matching only (no sameMantissaDifferentScale fallback): composing the
        // coefficient division with the scale-tolerant branch laundered phantom
        // values whose quotient merely shares a mantissa with a source across
        // decades (0.64/800 = 8e-4 ~ mantissa of 800 — the P-b72c-119 fabrication
        // would have passed).
        for (double a : hypothesisCoeffs) {
            if (!Double.isFinite(a) || Math.abs(a) < 1e-12 || a == 1.0) continue;
            double quotient = value / a;
            for (double p : permitted) {
                if (withinTolerance(quotient, p) || withinTolerance(quotient, Math.abs(p))) {
                    return true;
                }
            }
        }
        // x±y of two numbers the same computation produced, optionally
        // scaled by a hypothesis coefficient (covers k·(max−min) spreads).
        // Products are deliberately excluded — no observed false-positive class
        // needs them, and same-call products over a ~65-number verify result
        // would offer thousands of candidate values to launder against.
        for (List<Double> result : perResultNumbers) {
            int sz = result.size();
            for (int i = 0; i < sz; i++) {
                double x = result.get(i);
                for (int j = i + 1; j < sz; j++) {
                    double y = result.get(j);
                    double sum = x + y;
                    double diff = Math.abs(x - y);
                    if (withinTolerance(value, sum) || withinTolerance(value, diff)) {
                        return true;
                    }
                    for (double a : hypothesisCoeffs) {
                        if (!Double.isFinite(a) || Math.abs(a) < 1e-12 || a == 1.0) continue;
                        if (withinTolerance(value, a * sum) || withinTolerance(value, a * diff)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** ±1% relative match (same band as {@link #isPermittedNumber}'s strict branch). */
    private static boolean withinTolerance(double value, double candidate) {
        if (!Double.isFinite(candidate)) return false;
        if (candidate == 0.0) return value == 0.0;
        return Math.abs(value - candidate) / Math.abs(candidate) <= F5_5_RELATIVE_TOLERANCE;
    }

    /**
     * 2026-05-28 rec #1 — true when {@code a} and {@code b} share the same
     * leading significant digits (mantissa) within {@link #F5_5_RELATIVE_TOLERANCE}
     * and differ only by a power-of-ten unit scale no larger than
     * {@link #MAX_SCALE_DECADES} decades. Ported from
     * {@link CitationValidator#valueAppearsInResultScaleTolerant}. Used only as
     * a last-resort fallback in {@link #isPermittedNumber} after the strict and
     * magnitude comparisons fail, so it can only rescue a paper from a false
     * NULL, never launder a value that is genuinely off by more than a
     * unit-scale factor (the mantissa must still agree to within 1%).
     */
    static boolean sameMantissaDifferentScale(double a, double b) {
        if (a == 0.0 || b == 0.0 || !Double.isFinite(a) || !Double.isFinite(b)) return false;
        int aExp = (int) Math.floor(Math.log10(Math.abs(a)));
        int bExp = (int) Math.floor(Math.log10(Math.abs(b)));
        if (Math.abs(aExp - bExp) > MAX_SCALE_DECADES) return false;
        double aMant = Math.abs(a) / Math.pow(10.0, aExp);
        double bMant = Math.abs(b) / Math.pow(10.0, bExp);
        return Math.abs(aMant - bMant) <= F5_5_RELATIVE_TOLERANCE * Math.abs(bMant)
                || Math.abs(aMant - bMant) <= F5_5_RELATIVE_TOLERANCE * Math.abs(aMant);
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
     * {@code range_start}/{@code range_end} or {@code start}/{@code end} pairs,
     * or — for {@code verify_conjecture} — {@code window_start} +
     * {@code window_size}·{@code num_windows}. Calls whose result is errored /
     * rejected are skipped (they computed nothing). The union of the surviving
     * intervals is the executed-range set.
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
        // 2026-05-29 — verify_conjecture (Phase 23.5) reports its coverage as
        // window_start + window_size·num_windows, NOT range_start/range_end. Without
        // this, a conjecture paper's real coverage is invisible to the check and any
        // prose range reads as scope overreach (P-b72c-4 was nulled this way).
        Pattern windowArgs = Pattern.compile(
                "(?i)\"window_start\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?"
                        + "[^}]*?\"window_size\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?"
                        + "[^}]*?\"num_windows\"\\s*:\\s*\"?([0-9]+)\"?");
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            if (inv == null || inv.argsJson() == null) continue;
            // 2026-05-29 — only count ranges from calls that actually RAN. A rejected
            // call (window > MAX_N, predicate-scan span over cap, safety reject)
            // computed nothing, so its *requested* range must not enter the
            // executed-range union — otherwise it both credits coverage that never
            // happened and crowds out the real ranges. P-b72c-4: a rejected
            // predicate-scan's [200000,1000000) was the only range seen, hiding the
            // real verify_conjecture coverage at [1500000,1520000) → false NULL.
            // isErroredResult covers safety/runtime errors; the leading-"Invalid"
            // test catches the tool argument rejections ("Invalid verify_conjecture
            // call: … exceeds MAX_N", "Invalid predicate scan: …") that the shared
            // marker set does not, since those would otherwise credit the *requested*
            // (never-run) window/range.
            String resJson = inv.resultJson();
            if (resJson == null || resJson.isBlank()
                    || isErroredResult(resJson)
                    || resJson.stripLeading().regionMatches(true, 0, "Invalid", 0, 7)) {
                continue;
            }
            Matcher am = rangeArgs.matcher(inv.argsJson());
            while (am.find()) {
                try {
                    double a = Double.parseDouble(am.group(1));
                    double b = Double.parseDouble(am.group(2));
                    if (b > a) executed.add(new double[]{a, b});
                } catch (NumberFormatException ignored) {}
            }
            Matcher wm = windowArgs.matcher(inv.argsJson());
            while (wm.find()) {
                try {
                    double start = Double.parseDouble(wm.group(1));
                    double size  = Double.parseDouble(wm.group(2));
                    double n     = Double.parseDouble(wm.group(3));
                    double end   = start + size * n;
                    if (end > start) executed.add(new double[]{start, end});
                } catch (NumberFormatException ignored) {}
            }
        }
        if (executed.isEmpty()) return;

        // 2026-06-05 rec #1 — COALESCE adjacent/overlapping executed intervals before the
        // coverage test. The reason text always said "the executed-range union does not
        // cover it", but the loop below tested each prose claim against SINGLE intervals —
        // so a paper that ran the adjacent ladder the span-decomposition guidance teaches
        // ([1,300)+[300,600)+[600,900)+[900,1200)+[1200,1500), P-b72c-138) and honestly
        // summarized "over [1, 1500)" was false-NULL'd for the exact behaviour we steer it
        // toward. Merge with the same ±1% edge slack the coverage test uses, so window
        // ladders with hairline gaps still fuse; a REAL gap (e.g. [1,500) vs [800,1300))
        // stays unmerged and a claim spanning it still flags (P-b72c-137's [1000,1400)
        // beyond a union ending at 1000 remains a true positive).
        executed.sort(java.util.Comparator.comparingDouble(e -> e[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] ex : executed) {
            if (!merged.isEmpty()) {
                double[] last = merged.get(merged.size() - 1);
                double joinSlack = Math.max(Math.abs(last[1]), 1.0) * F5_5_RELATIVE_TOLERANCE;
                if (ex[0] <= last[1] + joinSlack) {
                    last[1] = Math.max(last[1], ex[1]);
                    continue;
                }
            }
            merged.add(new double[]{ex[0], ex[1]});
        }
        executed = merged;

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
            // 2026-06-04 rec #2 — only integer-like bounds are scan-range claims.
            // Every executed range here is an integer window over ℕ (range_start/
            // range_end, window_start + size·n). A fractional-bound interval in
            // prose is a VALUE band — a CI ("[0.006, 0.04)", P-b72c-118) or an
            // observed-scalar spread ("[0.7513, 0.7536)", P-b72c-120) — and
            // treating it as an unexecuted scan range false-NULLs the paper.
            if (a != Math.floor(a) || b != Math.floor(b)) continue;
            // 2026-06-05 rec #2 — "[0, 1)" is the canonical VALUE DOMAIN of a density /
            // fraction, not a scan-range claim (P-b72c-141 false-NULL: prose stating the
            // joint density lives in [0, 1) was read as an unexecuted range; integer
            // bounds, so the fractional-bound guard above passes it, and span 1 means no
            // executed range could ever cover it). Skip exactly the unit interval.
            if (a == 0.0 && b == 1.0) continue;

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
            if (isErroredResult(body)) continue;
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
