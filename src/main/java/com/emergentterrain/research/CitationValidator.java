package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 11 C3 — checks that every numeric value in published prose can be
 * traced to a real tool-call output. Three checks, in order:
 *
 * <ol>
 *   <li><b>Citation completeness</b> — every number in {@code hypothesis},
 *       {@code findings}, {@code conclusion} must be followed within ~25
 *       characters by a {@code [call_N]} marker. Numbers without markers are
 *       counted as {@code uncited}.</li>
 *   <li><b>Claim coverage</b> — every prose-extracted number must have a
 *       matching entry in {@link NarrationResult#numericClaims()} (value
 *       match within tolerance). Missing entries are counted as
 *       {@code unmatched}.</li>
 *   <li><b>Trace conformance</b> — every {@link NarrationResult.NumericClaim}
 *       must reference a {@code call_N} ID that exists in the trace, and the
 *       claim's {@code value} must appear in that call's {@code resultJson}
 *       (numeric match within tolerance). Failures are counted as
 *       {@code outOfTolerance}.</li>
 * </ol>
 *
 * <p>Excluded from numeric extraction: years 1700–2099, citation indices like
 * {@code [1]}, equation references like {@code (eq. 3)}, percentage values
 * in qualitative phrasing (heuristic). The exclusions are conservative; the
 * validator favours false negatives over false positives so warn-only mode
 * doesn't generate noise that drowns the real signal.
 *
 * <p>Tolerance: {@code |a - b| <= 0.005 * |b| + 1e-9}. Handles rounding
 * variations like "7.55" vs "7.553" — both pass when the underlying value is
 * 7.553. Also accepts common scientific-notation reformatting ("7.553e+00",
 * "0.007553e+3") because numbers are extracted from result text before
 * comparison.
 *
 * <p>Pre-Phase-11 traces (entries with {@code callId == null}) short-circuit
 * the validator to a clean result so existing papers in the corpus don't
 * spuriously fail validation when this gate is wired in {@link
 * com.emergentterrain.service.ResearchNarrationService}.
 */
@Service
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    /** Generic numeric token. Matches integers, decimals, scientific notation. */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z_])[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?![A-Za-z_])");

    /** Inline citation marker the LLM is asked to emit immediately after each numeric value. */
    private static final Pattern CALL_MARKER = Pattern.compile("\\[call_\\d+\\]");

    /** Years 1700–2099 — excluded from numeric extraction (historical references). */
    private static final Pattern YEAR = Pattern.compile("\\b(1[789]\\d\\d|20\\d\\d)\\b");

    /** Citation indices like {@code [1]}, {@code [12]} — excluded. */
    private static final Pattern CITATION_INDEX = Pattern.compile("\\[\\d{1,3}\\]");

    /** Equation references like {@code (eq. 3)} / {@code (Eq. 12)} — excluded. */
    private static final Pattern EQUATION_REF =
            Pattern.compile("\\(\\s*eq\\.?\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** Generic percentage in qualitative phrasing — excluded (heuristic). */
    private static final Pattern PERCENTAGE = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s*%");

    /**
     * Meta-precision phrases like {@code "4 sig figs"}, {@code "3 significant figures"},
     * {@code "2 decimal places"} — excluded. These are claims about precision, not
     * research results, so they don't need tool-call citations.
     */
    private static final Pattern META_PRECISION = Pattern.compile(
            "\\b\\d+\\s+(?:sig(?:nificant)?\\.?\\s+fig(?:ure)?s?|decimal\\s+places?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Maximum lookahead distance after a number, in characters, to find a {@code [call_N]} marker. */
    private static final int MARKER_LOOKAHEAD = 25;

    /** Default relative tolerance for numeric matching: 0.5% + tiny absolute floor. */
    private static final double REL_TOLERANCE = 0.005;
    private static final double ABS_FLOOR = 1e-9;

    /** Aggregate result of one paper's validation pass. */
    public record CitationValidationResult(
            int uncitedCount,
            int unmatchedClaimCount,
            int outOfToleranceCount,
            int exemptSweepCount,
            int hypothesisParamExemptCount,
            List<String> issues) {

        public boolean hasFailures() {
            return uncitedCount + unmatchedClaimCount + outOfToleranceCount > 0;
        }

        public int totalFailureCount() {
            return uncitedCount + unmatchedClaimCount + outOfToleranceCount;
        }

        public static CitationValidationResult clean() {
            return new CitationValidationResult(0, 0, 0, 0, 0, List.of());
        }

        /** Back-compat ctor (pre-2026-05-20 callers + tests that don't care
         *  about hypothesis exemption). Routes through the canonical
         *  constructor with {@code hypothesisParamExemptCount = 0}. */
        public CitationValidationResult(int uncitedCount,
                                         int unmatchedClaimCount,
                                         int outOfToleranceCount,
                                         int exemptSweepCount,
                                         List<String> issues) {
            this(uncitedCount, unmatchedClaimCount, outOfToleranceCount,
                    exemptSweepCount, 0, issues);
        }
    }

    /**
     * Validate a narration against its tool-call trace. Pre-Phase-11 traces
     * (callId == null on every entry) short-circuit to a clean result.
     *
     * <p>Back-compat overload: passes an empty sweep-parameter whitelist so
     * legacy callers behave identically to pre-Phase-21 §21.6.
     */
    public CitationValidationResult validate(
            NarrationResult narration,
            List<ToolInvocation> trace) {
        return validate(narration, trace, Set.of());
    }

    /**
     * Validate with an upstream-supplied sweep-parameter whitelist (Phase 21
     * §21.6). Numeric values in the hypothesis / findings / conclusion that
     * appear in {@code sweepParamWhitelist} are exempt from the "must have a
     * {@code [call_N]} marker" check — those values come from the
     * formulation step's pre-registered sweep params (e.g. PaperDraft's
     * {@code param1} / {@code param2}) and do not need to trace back to a
     * tool-call output. Counted into {@code exemptSweepCount} on the result.
     */
    public CitationValidationResult validate(
            NarrationResult narration,
            List<ToolInvocation> trace,
            Set<Double> sweepParamWhitelist) {
        if (narration == null) return CitationValidationResult.clean();
        if (trace == null || trace.isEmpty()) {
            // No tool calls happened (first-pass-only paper). The validator
            // has nothing to enforce against; treat as clean.
            return CitationValidationResult.clean();
        }
        boolean preP11Trace = trace.stream().allMatch(t -> t.callId() == null);
        if (preP11Trace) {
            return CitationValidationResult.clean();
        }

        // Build call_id -> tool-result lookup once.
        Map<String, String> traceById = new HashMap<>();
        for (ToolInvocation inv : trace) {
            if (inv.callId() != null) traceById.put(inv.callId(), inv.resultJson());
        }

        List<String> issues = new ArrayList<>();
        int uncited = 0;
        int unmatched = 0;
        int outOfTolerance = 0;
        int exemptSweep = 0;
        int hypothesisParamExempt = 0;
        Set<Double> whitelist = sweepParamWhitelist == null ? Set.of() : sweepParamWhitelist;

        // Collect (number, sourceLocation, citedCallId) tuples from every prose block.
        List<ProseNumber> proseNumbers = new ArrayList<>();
        proseNumbers.addAll(extractNumbers(narration.hypothesis(), "hypothesis"));
        if (narration.findingsMap() != null) {
            for (Map.Entry<Integer, String> e : narration.findingsMap().entrySet()) {
                proseNumbers.addAll(extractNumbers(e.getValue(), "findings:" + e.getKey()));
            }
        }
        proseNumbers.addAll(extractNumbers(narration.conclusion(), "conclusion"));

        // Check 1 — citation completeness.
        //
        // Phase 21 §21.6 — sweep params exempted (Check 1a): PaperDraft.param1
        // / param2 routed through the hypothesis template don't have a
        // [call_N] marker because they predate the tool-call chain; counted
        // into exemptSweepCount.
        //
        // 2026-05-20 — hypothesis-source exemption (Check 1b): any prose
        // number that appears in the hypothesis itself is a pre-registration
        // value (parameter, physical constant, formula coefficient, prior
        // empirical value) and by definition predates the tool-call chain.
        // The previous validator routinely demoted papers like P-a328-751
        // (T=450K and L=4.58nm in the hypothesis, both consumed by the tool
        // call's args) because the hypothesis-side values had no [call_N]
        // marker. The trace-conformance check (Check 3) still catches any
        // value the LLM later *claims* is a derived result, so suppressing
        // hypothesis uncited-flags here doesn't open a fabrication hole.
        for (ProseNumber pn : proseNumbers) {
            if (pn.citedCallId == null) {
                if (isWhitelisted(pn.value, whitelist)) {
                    exemptSweep++;
                    continue;
                }
                if (pn.sourceLocation != null
                        && pn.sourceLocation.startsWith("hypothesis")) {
                    hypothesisParamExempt++;
                    continue;
                }
                uncited++;
                issues.add(String.format("uncited number %s in %s", pn.text, pn.sourceLocation));
            }
        }

        // Check 2 — claim coverage. Whitelisted sweep params are skipped
        // here too (an exempted value isn't expected to have a matching
        // NumericClaim entry — that's the whole point of the exemption).
        List<NarrationResult.NumericClaim> claims = narration.numericClaims() == null
                ? Collections.emptyList() : narration.numericClaims();
        for (ProseNumber pn : proseNumbers) {
            if (pn.citedCallId == null) continue;  // already counted as uncited / exempt
            if (isWhitelisted(pn.value, whitelist)) continue;
            boolean covered = claims.stream()
                    .anyMatch(c -> withinTolerance(pn.value, c.value()));
            if (!covered) {
                unmatched++;
                issues.add(String.format(
                        "prose number %s in %s has no matching numeric_claims entry",
                        pn.text, pn.sourceLocation));
            }
        }

        // Check 3 — trace conformance for each claim.
        for (NarrationResult.NumericClaim c : claims) {
            String callId = c.toolCallId();
            if (callId == null || callId.isBlank()) {
                outOfTolerance++;
                issues.add(String.format(
                        "numeric_claim value=%s has no tool_call_id", c.value()));
                continue;
            }
            String resultJson = traceById.get(callId);
            if (resultJson == null) {
                outOfTolerance++;
                issues.add(String.format(
                        "%s referenced by claim value=%s is not in trace", callId, c.value()));
                continue;
            }
            if (!valueAppearsInResult(c.value(), resultJson)) {
                outOfTolerance++;
                issues.add(String.format(
                        "%s result does not contain value %s (claimed in %s)",
                        callId, c.value(), c.sourceLocation()));
            }
        }

        if (!issues.isEmpty() || exemptSweep > 0 || hypothesisParamExempt > 0) {
            log.debug("CitationValidator: uncited={} unmatched={} out_of_tolerance={} "
                            + "exempt_sweep={} exempt_hyp={} issues={}",
                    uncited, unmatched, outOfTolerance,
                    exemptSweep, hypothesisParamExempt, issues);
        }
        return new CitationValidationResult(uncited, unmatched, outOfTolerance,
                exemptSweep, hypothesisParamExempt, List.copyOf(issues));
    }

    /**
     * Is a prose-extracted number within tolerance of any whitelisted sweep
     * parameter? Uses the same {@link #withinTolerance} relation as the
     * trace-conformance check so e.g. param1=17 in prose matches a whitelist
     * entry of 17.0 even when one side is an int and the other a double.
     */
    private static boolean isWhitelisted(double value, Set<Double> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) return false;
        for (Double w : whitelist) {
            if (w == null) continue;
            if (withinTolerance(value, w)) return true;
        }
        return false;
    }

    // ────────────────── helpers ──────────────────

    private record ProseNumber(double value, String text, int endIndex,
                                String sourceLocation, String citedCallId) {}

    /**
     * Extract numeric tokens from a prose block, filtering out years / citation
     * indices / equation refs / qualitative percentages. Each extracted number
     * is tagged with the {@code [call_N]} marker (if any) found within
     * {@link #MARKER_LOOKAHEAD} characters after it; absent marker → null.
     */
    static List<ProseNumber> extractNumbers(String text, String sourceLocation) {
        if (text == null || text.isBlank()) return List.of();
        // Mask exclusions with spaces of equal length so character offsets line up.
        String masked = text;
        masked = YEAR.matcher(masked).replaceAll(m -> " ".repeat(m.end() - m.start()));
        masked = CITATION_INDEX.matcher(masked).replaceAll(m -> " ".repeat(m.end() - m.start()));
        masked = EQUATION_REF.matcher(masked).replaceAll(m -> " ".repeat(m.end() - m.start()));
        masked = PERCENTAGE.matcher(masked).replaceAll(m -> " ".repeat(m.end() - m.start()));
        masked = META_PRECISION.matcher(masked).replaceAll(m -> " ".repeat(m.end() - m.start()));

        List<ProseNumber> out = new ArrayList<>();
        Matcher num = NUMBER.matcher(masked);
        while (num.find()) {
            String tok = num.group();
            double value;
            try {
                value = Double.parseDouble(tok);
            } catch (NumberFormatException ignore) {
                continue;
            }
            int end = num.end();
            String citedCallId = findCallMarkerNear(text, end);
            out.add(new ProseNumber(value, tok, end, sourceLocation, citedCallId));
        }
        return out;
    }

    /**
     * Scan forward from {@code fromIndex} up to {@link #MARKER_LOOKAHEAD}
     * characters in the original (unmasked) text for a {@code [call_N]}
     * marker. Returns the captured ID like "call_2" or null when none found.
     */
    private static String findCallMarkerNear(String text, int fromIndex) {
        int limit = Math.min(text.length(), fromIndex + MARKER_LOOKAHEAD);
        if (fromIndex >= limit) return null;
        Matcher m = CALL_MARKER.matcher(text.substring(fromIndex, limit));
        if (!m.find()) return null;
        // Extract the "call_N" body without brackets.
        String full = m.group();
        return full.substring(1, full.length() - 1);
    }

    /** Tolerance check: relative 0.5% plus absolute floor for near-zero values. */
    static boolean withinTolerance(double a, double b) {
        return Math.abs(a - b) <= REL_TOLERANCE * Math.abs(b) + ABS_FLOOR
                || Math.abs(a - b) <= REL_TOLERANCE * Math.abs(a) + ABS_FLOOR;
    }

    /**
     * Search a tool-result string for a numeric value within tolerance. The
     * result is plain text (often JSON-ish prose like {@code "Param sweep
     * (param1=20, param2=2.0): mean=78.0±7.0 [Rationale: ...]"}), so we extract
     * numbers with the same regex and compare.
     */
    static boolean valueAppearsInResult(double target, String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return false;
        Matcher m = NUMBER.matcher(resultJson);
        while (m.find()) {
            String tok = m.group();
            try {
                double v = Double.parseDouble(tok);
                if (withinTolerance(target, v)) return true;
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return false;
    }
}
