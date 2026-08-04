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

    /**
     * Generic numeric token. Matches integers, decimals, scientific notation, and
     * thousands-grouped integers ({@code 1,450,007}). The grouped alternative
     * ({@code \d{1,3}(?:,\d{3})+}) must precede the plain {@code \d+} so a comma-grouped
     * value is captured whole instead of fragmenting into per-group tokens — a fragmented
     * {@code 200,003} → {@code 200}/{@code 003} matches nothing in the tool trace and used
     * to inflate unmatched/out-of-tolerance counts into spurious NULL_RESULT downgrades.
     * A comma followed by anything other than exactly three digits (e.g. list commas like
     * "1, 2, 3") is not consumed, so genuine separate numbers stay separate. Parse matched
     * tokens via {@link #parseGrouped} (strips the separators before {@link Double#parseDouble}).
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z_])[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?![A-Za-z_])");

    /** Parse a {@link #NUMBER} token, stripping thousands-separator commas first. */
    private static double parseGrouped(String token) {
        return Double.parseDouble(token.indexOf(',') < 0 ? token : token.replace(",", ""));
    }

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

    /**
     * Maximum power-of-ten gap tolerated by the scale-invariant reconcile match
     * ({@link #valueAppearsInResultScaleTolerant}). Covers scaling a Planck-scale
     * quantity (~1e-34) up to O(1) for readability; gaps beyond this are treated
     * as coincidental and not reconciled.
     */
    private static final int MAX_SCALE_DECADES = 40;

    /** Aggregate result of one paper's validation pass. */
    /**
     * Room b72c4e10 fix (Rec 5) — one prose-side numeric value the validator
     * could not trace, with enough context to excise just the sentence that
     * carries it (rather than nulling the whole paper). {@code sourceLocation}
     * is the prose block ({@code "findings:1"}, {@code "conclusion"}); only
     * Check-1 (uncited) and Check-2 (unmatched-claim) rejections produce these
     * — Check-3 trace-conformance failures are claim-side fabrications that stay
     * whole-paper nullable.
     */
    public record RejectedProseNumber(double value, String text, String sourceLocation) {}

    public record CitationValidationResult(
            int uncitedCount,
            int unmatchedClaimCount,
            int outOfToleranceCount,
            int exemptSweepCount,
            int hypothesisParamExemptCount,
            List<String> issues,
            List<RejectedProseNumber> rejectedProseNumbers) {

        public boolean hasFailures() {
            return uncitedCount + unmatchedClaimCount + outOfToleranceCount > 0;
        }

        public int totalFailureCount() {
            return uncitedCount + unmatchedClaimCount + outOfToleranceCount;
        }

        /**
         * True iff every failure is prose-side (uncited / unmatched-claim) — i.e.
         * there are no Check-3 trace-conformance ({@code outOfTolerance})
         * failures. Sentence-level quarantine is only safe in this case: an
         * out-of-tolerance claim asserts a tool produced a value it did not, a
         * fabrication that must null the paper, not be edited away.
         */
        public boolean allFailuresAreProseSide() {
            return hasFailures() && outOfToleranceCount == 0;
        }

        public static CitationValidationResult clean() {
            return new CitationValidationResult(0, 0, 0, 0, 0, List.of(), List.of());
        }

        /** Back-compat ctor (pre-2026-05-20 callers + tests that don't care
         *  about hypothesis exemption). Routes through the canonical
         *  constructor with {@code hypothesisParamExemptCount = 0} and no
         *  rejected-prose-number detail. */
        public CitationValidationResult(int uncitedCount,
                                         int unmatchedClaimCount,
                                         int outOfToleranceCount,
                                         int exemptSweepCount,
                                         List<String> issues) {
            this(uncitedCount, unmatchedClaimCount, outOfToleranceCount,
                    exemptSweepCount, 0, issues, List.of());
        }

        /** Back-compat ctor (pre-Rec-5 6-arg callers). */
        public CitationValidationResult(int uncitedCount,
                                         int unmatchedClaimCount,
                                         int outOfToleranceCount,
                                         int exemptSweepCount,
                                         int hypothesisParamExemptCount,
                                         List<String> issues) {
            this(uncitedCount, unmatchedClaimCount, outOfToleranceCount,
                    exemptSweepCount, hypothesisParamExemptCount, issues, List.of());
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
        return validate(narration, trace, sweepParamWhitelist, false);
    }

    /**
     * Validate with reconciliation control (2026-05-26 — recommendation #1 of
     * the research/a3289005 review). When {@code reconcileTraceable} is true,
     * any uncited / unmatched prose number, or any claim citing a missing /
     * wrong {@code call_N}, whose value nonetheless <em>appears somewhere in
     * the tool-call trace</em> is reclassified as traceable rather than counted
     * as a failure. The narrator simply failed to attach the right
     * {@code [call_N]} marker; the value is real.
     *
     * <p>This is the fix for the dominant batch defect: papers whose tool calls
     * produced correct results (E₁/k_BT=2.07 in P-a328-960's call_4; 9 happy
     * primes in P-a328-968's call_4; 1/ln(530)=0.159 in P-a328-969's call_4)
     * but whose narrator dropped the citation, so the gate stripped the numbers,
     * downgraded to NULL_RESULT, and rewrote the prose to claim "the hypothesis
     * remains untested" — directly contradicting the trace.
     *
     * <p>Reconciliation is strictly more rigorous than the pre-existing
     * hypothesis-source exemption (which trusts every hypothesis number with no
     * trace check at all): a value is reconciled only when it is demonstrably
     * present in a real tool result, so it cannot launder a fabricated number.
     * A value present in NO trace result (e.g. P-a328-960's hallucinated "3.25"
     * borrowed from a sibling paper) still fails.
     */
    public CitationValidationResult validate(
            NarrationResult narration,
            List<ToolInvocation> trace,
            Set<Double> sweepParamWhitelist,
            boolean reconcileTraceable) {
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
        // Reconciliation corpus: all tool-result text, scanned when a prose
        // number lacks a clean citation but may still be present in the trace.
        java.util.Collection<String> allResults = traceById.values();

        List<String> issues = new ArrayList<>();
        int uncited = 0;
        int unmatched = 0;
        int outOfTolerance = 0;
        int exemptSweep = 0;
        int hypothesisParamExempt = 0;
        int autoReconciled = 0;
        // rec #9 — raw values of every flagged number, paired with their nearest
        // trace token at end-of-run so a residual false-null can be triaged
        // (mantissa/scale mismatch vs genuinely-absent) without re-running the paper.
        List<Double> rejectedValues = new ArrayList<>();
        // Room b72c4e10 Rec 5 — prose-side rejections (uncited / unmatched-claim),
        // carried with their source block so a sentence-level quarantine can
        // excise just the offending sentence instead of nulling the whole paper.
        List<RejectedProseNumber> rejectedProse = new ArrayList<>();
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
        //
        // 2026-05-26 — reconcile-before-null (Check 1c): a findings/conclusion
        // number with no marker but a value present in the trace is traceable.
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
                if (reconcileTraceable && appearsInAnyResult(pn.value, allResults)) {
                    autoReconciled++;
                    continue;
                }
                uncited++;
                rejectedValues.add(pn.value);
                rejectedProse.add(new RejectedProseNumber(pn.value, pn.text, pn.sourceLocation));
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
                if (reconcileTraceable && appearsInAnyResult(pn.value, allResults)) {
                    autoReconciled++;
                    continue;
                }
                unmatched++;
                rejectedValues.add(pn.value);
                rejectedProse.add(new RejectedProseNumber(pn.value, pn.text, pn.sourceLocation));
                issues.add(String.format(
                        "prose number %s in %s has no matching numeric_claims entry",
                        pn.text, pn.sourceLocation));
            }
        }

        // Check 3 — trace conformance for each claim.
        for (NarrationResult.NumericClaim c : claims) {
            String callId = c.toolCallId();
            if (callId == null || callId.isBlank()) {
                if (reconcileTraceable && appearsInAnyResult(c.value(), allResults)) {
                    autoReconciled++;
                    continue;
                }
                outOfTolerance++;
                rejectedValues.add(c.value());
                issues.add(String.format(
                        "numeric_claim value=%s has no tool_call_id", c.value()));
                continue;
            }
            String resultJson = traceById.get(callId);
            if (resultJson == null) {
                // Claim cites a call_N that isn't in the trace. If the value is
                // present in some *other* call's result, the narrator mis-attributed
                // the marker but the value is real — reconcile rather than fail.
                if (reconcileTraceable && appearsInAnyResult(c.value(), allResults)) {
                    autoReconciled++;
                    continue;
                }
                outOfTolerance++;
                rejectedValues.add(c.value());
                issues.add(String.format(
                        "%s referenced by claim value=%s is not in trace", callId, c.value()));
                continue;
            }
            if (!valueAppearsInResult(c.value(), resultJson)) {
                if (reconcileTraceable && appearsInAnyResult(c.value(), allResults)) {
                    autoReconciled++;
                    continue;
                }
                outOfTolerance++;
                rejectedValues.add(c.value());
                issues.add(String.format(
                        "%s result does not contain value %s (claimed in %s)",
                        callId, c.value(), c.sourceLocation()));
            }
        }

        if (autoReconciled > 0) {
            log.info("CitationValidator: reconcile-before-null reclassified {} traceable "
                    + "value(s) that lacked a clean [call_N] citation (remaining uncited={} "
                    + "unmatched={} out_of_tolerance={})",
                    autoReconciled, uncited, unmatched, outOfTolerance);
        }
        if (!issues.isEmpty() || exemptSweep > 0 || hypothesisParamExempt > 0) {
            log.debug("CitationValidator: uncited={} unmatched={} out_of_tolerance={} "
                            + "exempt_sweep={} exempt_hyp={} reconciled={} issues={}",
                    uncited, unmatched, outOfTolerance,
                    exemptSweep, hypothesisParamExempt, autoReconciled, issues);
        }
        // rec #9 — per-rejection diagnostics. For every flagged value, log its
        // nearest trace token and relative gap so a residual false-null is
        // triageable from the log alone: a tiny gap (≈ REL_TOLERANCE) points at a
        // formatting/precision miss, a clean power-of-ten gap at a unit-scale miss
        // (handled by the scale-tolerant reconcile), and a large gap at a value
        // genuinely absent from the trace.
        if (!rejectedValues.isEmpty()) {
            StringBuilder diag = new StringBuilder();
            for (double rv : rejectedValues) {
                if (diag.length() > 0) diag.append("; ");
                java.util.OptionalDouble near = nearestTraceValue(rv, allResults);
                if (near.isPresent()) {
                    diag.append("%.6g→nearest %.6g (relΔ=%.3g)".formatted(
                            rv, near.getAsDouble(), relativeGap(rv, near.getAsDouble())));
                } else {
                    diag.append("%.6g→no trace tokens".formatted(rv));
                }
            }
            log.info("CitationValidator: rejection diagnostics (rec#9) rel_tol={} "
                            + "uncited={} unmatched={} out_of_tolerance={} | {}",
                    REL_TOLERANCE, uncited, unmatched, outOfTolerance, diag);
        }
        return new CitationValidationResult(uncited, unmatched, outOfTolerance,
                exemptSweep, hypothesisParamExempt, List.copyOf(issues),
                List.copyOf(rejectedProse));
    }

    /**
     * Relative gap between two values: {@code |a-b| / max(|a|,|b|)}, 0 when both
     * are within the absolute floor of zero. Used for rec #9 rejection diagnostics.
     */
    static double relativeGap(double a, double b) {
        double denom = Math.max(Math.abs(a), Math.abs(b));
        return denom < ABS_FLOOR ? 0.0 : Math.abs(a - b) / denom;
    }

    /**
     * Nearest numeric token to {@code target} (by {@link #relativeGap}) across
     * all tool-result texts, for rec #9 rejection diagnostics. Empty when no
     * parseable number is present in any result.
     */
    static java.util.OptionalDouble nearestTraceValue(
            double target, java.util.Collection<String> results) {
        Double best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        if (results != null) {
            for (String r : results) {
                if (r == null) continue;
                Matcher m = NUMBER.matcher(r);
                while (m.find()) {
                    try {
                        double v = parseGrouped(m.group());
                        double gap = relativeGap(target, v);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = v;
                        }
                    } catch (NumberFormatException ignore) {
                        // skip
                    }
                }
            }
        }
        return best == null ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(best);
    }

    /**
     * True when {@code value} appears in any tool result — first by a direct
     * within-tolerance match, then by a scale-invariant fallback that ignores a
     * power-of-ten unit difference. Used only by the reconcile-before-null
     * rescue path; the strict Check-3 conformance check
     * ({@link #valueAppearsInResult} against the specifically cited call) is
     * left unchanged, so this looser match cannot launder a mis-cited claim.
     */
    private static boolean appearsInAnyResult(double value, java.util.Collection<String> results) {
        if (results == null) return false;
        for (String r : results) {
            if (valueAppearsInResult(value, r)
                    || valueAppearsInResultScaleTolerant(value, r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scale-invariant ("same significant digits, different unit scale") match,
     * used ONLY in the reconcile-before-null rescue path. Sandbox code routinely
     * records a scaled value for readability — e.g. {@code recordSample(kT * 1e21)}
     * emits {@code 6.95847} — while the prose reports the physical quantity
     * {@code 6.958e-21 J}. A raw numeric compare misses it by 21 orders of
     * magnitude even though it is the same computed value (this was the dominant
     * false-NULL cause in the research/a3289005 batch — P-a328-987 et al.).
     *
     * <p>Matches when the mantissas agree within {@link #REL_TOLERANCE} — which
     * forces the ratio to a clean power of ten — and the decade gap is within
     * {@link #MAX_SCALE_DECADES}. Because it lives only in the reconcile corpus
     * scan and never in the strict per-call conformance check, it can only
     * rescue a paper from a false NULL downgrade, not validate a fabricated or
     * mis-attributed claim.
     */
    static boolean valueAppearsInResultScaleTolerant(double target, String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return false;
        if (target == 0.0 || !Double.isFinite(target)) return false;
        int targetExp = (int) Math.floor(Math.log10(Math.abs(target)));
        double targetMantissa = Math.abs(target) / Math.pow(10.0, targetExp);
        Matcher m = NUMBER.matcher(resultJson);
        while (m.find()) {
            double v;
            try {
                v = parseGrouped(m.group());
            } catch (NumberFormatException ignore) {
                continue;
            }
            if (v == 0.0 || !Double.isFinite(v)) continue;
            int vExp = (int) Math.floor(Math.log10(Math.abs(v)));
            if (Math.abs(targetExp - vExp) > MAX_SCALE_DECADES) continue;
            double vMantissa = Math.abs(v) / Math.pow(10.0, vExp);
            if (withinTolerance(targetMantissa, vMantissa)) return true;
        }
        return false;
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
     * Room b72c4e10 Rec 5 — sentence-level quarantine. Remove from {@code block}
     * each sentence that contains one of the {@code rejectedTexts} (the verbatim
     * numeric tokens the validator flagged in this block), leaving the rest of
     * the prose intact. Returns the surviving prose (trimmed, sentences re-joined
     * with a single space), or empty string if every sentence was removed.
     *
     * <p>Sentence splitting is deliberately simple — split on {@code . }, {@code ! },
     * {@code ? } boundaries, keeping the terminator — because the narrator's prose
     * is plain declarative sentences; a rejected number lives in exactly one of
     * them. Matching is on the exact token surrounded by non-digit boundaries so
     * "0.143" doesn't also strike a sentence mentioning "0.1437". Pure + static
     * for unit testing.
     */
    public static String quarantineSentences(String block, List<String> rejectedTexts) {
        if (block == null || block.isBlank() || rejectedTexts == null || rejectedTexts.isEmpty()) {
            return block == null ? "" : block;
        }
        // Split into sentences on a terminator (. ! ?) FOLLOWED BY whitespace —
        // so a decimal point inside a number ("0.143") never splits, because it
        // is followed by a digit, not whitespace. The terminator stays attached
        // to the sentence it ends (lookbehind split). A trailing terminator with
        // no following whitespace (end of block) leaves the last sentence whole.
        List<String> sentences = new ArrayList<>();
        for (String s : block.split("(?<=[.!?])\\s+")) {
            if (!s.isBlank()) sentences.add(s.trim());
        }
        if (sentences.isEmpty()) sentences.add(block.trim());

        StringBuilder kept = new StringBuilder();
        for (String sentence : sentences) {
            boolean carriesRejected = false;
            for (String tok : rejectedTexts) {
                if (tok != null && !tok.isBlank() && containsNumberToken(sentence, tok)) {
                    carriesRejected = true;
                    break;
                }
            }
            if (!carriesRejected) {
                if (kept.length() > 0) kept.append(' ');
                kept.append(sentence);
            }
        }
        return kept.toString().trim();
    }

    /**
     * True iff {@code sentence} contains {@code token} as a standalone numeric
     * token (not as a substring of a longer number). Reuses the digit-boundary
     * discipline of {@link #NUMBER} so "0.143" matches "0.143" but not "0.1437".
     */
    public static boolean containsNumberToken(String sentence, String token) {
        if (sentence == null || token == null || token.isEmpty()) return false;
        int from = 0;
        while (true) {
            int idx = sentence.indexOf(token, from);
            if (idx < 0) return false;
            char before = idx == 0 ? ' ' : sentence.charAt(idx - 1);
            int after = idx + token.length();
            char afterCh = after >= sentence.length() ? ' ' : sentence.charAt(after);
            // A '.' bounds the token only when it continues the number (followed by
            // a digit, e.g. "0.1437"); a sentence-terminating '.' ("0.143.") does
            // not. Same for the left side.
            char afterAfter = (after + 1) >= sentence.length() ? ' ' : sentence.charAt(after + 1);
            boolean leftOk = !Character.isDigit(before)
                    && !(before == '.' && idx >= 2 && Character.isDigit(sentence.charAt(idx - 2)));
            boolean rightOk = !Character.isDigit(afterCh)
                    && !(afterCh == '.' && Character.isDigit(afterAfter));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

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
                value = parseGrouped(tok);
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
                double v = parseGrouped(tok);
                if (withinTolerance(target, v)) return true;
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return false;
    }
}
