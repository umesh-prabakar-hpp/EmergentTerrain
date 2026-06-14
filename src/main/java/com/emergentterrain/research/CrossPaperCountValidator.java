package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 32 (paper-pipeline-quality-brief Change 6, core) — cross-paper numeric reconciliation for
 * exhaustive predicate scans. Independent papers on overlapping integer ranges were reporting counts
 * that cannot all be true (e.g. 11 happy-primes in [400,800) but only 10 in the wider [400,850)) — and
 * nothing reconciled them. Since {@code run_predicate_scan} now reports an EXACT count over a full
 * interval (Change 5), the check is purely mechanical and LLM-independent:
 *
 * <ul>
 *   <li><b>Containment monotonicity:</b> if range A ⊆ range B on the same predicate, then
 *       count(A) ≤ count(B). A sub-interval cannot contain more matches than a super-interval.</li>
 *   <li><b>Same-range consistency:</b> identical ranges on the same predicate must report the same
 *       count (the scan is deterministic; a disagreement is a real inconsistency).</li>
 * </ul>
 *
 * <p>Pure and stateless: {@link #fromTrace} extracts the {@link ScanCount}s a paper produced, and
 * {@link #validate} checks a set (this paper's ∪ the persisted counts from prior papers) for the
 * relations above. No sampling assumptions — the counts are exact.
 */
public final class CrossPaperCountValidator {

    private CrossPaperCountValidator() {}

    /** An exact predicate-scan count: {@code count} matches over the half-open range [start, end). */
    public record ScanCount(String predicate, long start, long end, long count) {}

    // run_predicate_scan args carry the predicate + range; the result carries the exact "N/M" count
    // (Change 5: "... = <count>/<size> = <fraction> (exact count over the full interval ...)").
    private static final Pattern ARG_PREDICATE = Pattern.compile("\"predicate\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ARG_START = Pattern.compile("\"range_start\"\\s*:\\s*(-?\\d+)");
    private static final Pattern ARG_END = Pattern.compile("\"range_end\"\\s*:\\s*(-?\\d+)");
    private static final Pattern RESULT_COUNT = Pattern.compile("=\\s*(\\d+)/(\\d+)\\s*=");

    /**
     * Extract the exact-count scans a paper produced from its tool-call trace. Only
     * {@code run_predicate_scan} calls whose result carries the exact "N/M" count are included
     * (pre-Change-5 results with a "± CI" are skipped — no exact count to reconcile). Null/empty → [].
     */
    public static List<ScanCount> fromTrace(List<ToolInvocation> trace) {
        List<ScanCount> out = new ArrayList<>();
        if (trace == null) {
            return out;
        }
        for (ToolInvocation call : trace) {
            if (call == null || !"run_predicate_scan".equals(call.functionName())) {
                continue;
            }
            String predicate = group(ARG_PREDICATE, call.argsJson());
            Long start = num(ARG_START, call.argsJson());
            Long end = num(ARG_END, call.argsJson());
            Long count = num(RESULT_COUNT, call.resultJson());   // group 1 = exact count
            if (predicate != null && start != null && end != null && count != null && end > start) {
                out.add(new ScanCount(predicate.trim(), start, end, count));
            }
        }
        return out;
    }

    /**
     * Check a set of exact counts (this paper's ∪ the persisted prior-paper counts) for mutually
     * impossible relations. Returns one human-readable string per violation; empty when consistent.
     */
    public static List<String> validate(List<ScanCount> counts) {
        List<String> violations = new ArrayList<>();
        if (counts == null || counts.size() < 2) {
            return violations;
        }
        for (int i = 0; i < counts.size(); i++) {
            for (int j = i + 1; j < counts.size(); j++) {
                ScanCount a = counts.get(i);
                ScanCount b = counts.get(j);
                if (a.predicate() == null || !a.predicate().equals(b.predicate())) {
                    continue;
                }
                boolean sameRange = a.start() == b.start() && a.end() == b.end();
                if (sameRange) {
                    if (a.count() != b.count()) {
                        violations.add(String.format(
                                "same range [%d,%d) on '%s' reports inconsistent counts %d vs %d",
                                a.start(), a.end(), a.predicate(), a.count(), b.count()));
                    }
                    continue;
                }
                // containment monotonicity: a sub-range cannot out-count its super-range.
                ScanCount sub = contains(b, a) ? a : (contains(a, b) ? b : null);
                ScanCount sup = (sub == a) ? b : (sub == b) ? a : null;
                if (sub != null && sub.count() > sup.count()) {
                    violations.add(String.format(
                            "'%s': sub-range [%d,%d) count %d exceeds containing [%d,%d) count %d "
                                    + "(impossible — a sub-interval cannot have more matches)",
                            sub.predicate(), sub.start(), sub.end(), sub.count(),
                            sup.start(), sup.end(), sup.count()));
                }
            }
        }
        return violations;
    }

    /** True when {@code inner}'s range is contained in {@code outer}'s (and they are not identical). */
    private static boolean contains(ScanCount outer, ScanCount inner) {
        return inner.start() >= outer.start() && inner.end() <= outer.end()
                && !(inner.start() == outer.start() && inner.end() == outer.end());
    }

    // ── persistence round-trip (capture writes toFact; a future fetch reads fromFact) ──────────────

    /** Serialize for the research-memory store: {@code scan-count: <predicate> | <start> | <end> | <count>}. */
    public static String toFact(ScanCount c) {
        return "scan-count: " + c.predicate() + " | " + c.start() + " | " + c.end() + " | " + c.count();
    }

    private static final Pattern FACT = Pattern.compile(
            "scan-count:\\s*(.+?)\\s*\\|\\s*(-?\\d+)\\s*\\|\\s*(-?\\d+)\\s*\\|\\s*(\\d+)");

    /** Parse a {@link #toFact} line back to a {@link ScanCount}; null if it doesn't match. */
    public static ScanCount fromFact(String content) {
        if (content == null) {
            return null;
        }
        Matcher m = FACT.matcher(content);
        return m.find()
                ? new ScanCount(m.group(1).trim(), Long.parseLong(m.group(2)),
                        Long.parseLong(m.group(3)), Long.parseLong(m.group(4)))
                : null;
    }

    private static String group(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Long num(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        try {
            return m.find() ? Long.valueOf(m.group(1)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
