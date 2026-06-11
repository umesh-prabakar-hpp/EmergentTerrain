package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.repo.ConjectureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-06-09 — the discovery→pool write-back (step 1). When a
 * conjecture-investigation paper's professor proves a bound HELD at the failure
 * region via {@code verify_conjecture} — e.g. {@code max_prime_gap ≤
 * 1·log_sq_window_start + 0} holds at x=1,800,000 — write that bound back into
 * the {@code conjectures} pool with the professor's tuned (k,c), so the relation
 * becomes a live, selectable topic instead of the discovery evaporating.
 *
 * <p>Motivation (2026-06-09): the miner had SEEDED these relations but RETIRED
 * them with its K_GRID fit (e.g. {@code max_prime_gap ≤ 2·log_sq_window_start −
 * 188}, private_validator failed) — yet the professor's {@code k=1, c=0} HOLDS.
 * The write-back revives the relation with the holding fit.
 *
 * <p>Flag-gated OFF ({@code app.research.investigation-write-back.enabled},
 * default false). Step 1 only: it revives/inserts the relation and stamps a
 * durable {@code investigationVerifiedAt}; the next miner pass still re-fits on
 * its small prototype windows (the "decision A" — make the miner respect the
 * verified window geometry — is deferred until the small-window re-refutation
 * rate is observed under this flag).
 */
@Service
public class ConjectureInvestigationWriteBackService {

    private static final Logger log =
            LoggerFactory.getLogger(ConjectureInvestigationWriteBackService.class);

    private static final String DOMAIN = "math";
    /** Only write back a bound the professor verified AT the failure region (high x),
     *  not an easy low-x region where any loose bound holds. */
    private static final long FAILURE_REGION_MIN_X = 1_000_000L;
    /** Require some adversarial coverage. */
    private static final int MIN_WINDOWS = 3;
    /**
     * 2026-06-10 (tight-bound frontier, lever B) — the most a written-back bound may slack at its
     * binding window: {@code |margin| / |bound|} above this is "loose" — the flat-feature refutation
     * dressed up, not a tight survivor — and is NOT enshrined in the pool. A bound with no parsed
     * relative-slack figure (older traces / non-emitting paths) is admitted as before (lenient).
     * Lenient ceiling: rejects egregiously loose bounds (e.g. a k=1 growth anchor at ~0.65) while
     * still admitting numerically-tight survivors.
     */
    private static final double MAX_REL_SLACK = 0.25;

    /** Parses a verify_conjecture result line (MathToolSuite format). The trailing relative-slack
     *  figure is OPTIONAL (HELD lines emit it since 2026-06-10; refuted/older lines do not). */
    private static final Pattern HELD = Pattern.compile(
            "verify_conjecture: ([a-zA-Z_]+) <= ([+-][0-9.]+) \\* ([a-zA-Z_]+) \\+ "
                    + "([+-][0-9.]+) HELD across ([0-9]+) window\\(s\\) of size [0-9]+ "
                    + "starting at ([0-9]+)\\. worst margin: ([+-][0-9.]+)"
                    + "(?: at window starting [0-9]+\\. relative slack: ([0-9.]+))?");

    // 2026-06-10 (lever D) — measured-invariant result lines (frames 1-3). A CONVERGED constant
    // (verify_constant / verify_growth) or a centred OSCILLATES envelope (verify_oscillation) at the
    // failure region is a verified MEASUREMENT, persisted as a kind-tagged pool survivor.
    // .*? is reluctant and (no DOTALL) stays within the single summary line — the measured tools emit
    // one-line results (unlike verify_conjecture's per-window detail). [^.]/[^|] can't be used: dev values
    // contain '.' and the oscillation line contains '|' (mean|.|, max|lhs|).
    private static final Pattern CONST_CONVERGED = Pattern.compile(
            "verify_constant: ([a-zA-Z_]+) ~ [+-]?[0-9.]+ over [0-9]+ window\\(s\\) of size [0-9]+ "
                    + "starting at ([0-9]+)\\. CONVERGED to target ([+-]?[0-9.]+): mean rel-dev ([0-9.]+)");
    private static final Pattern GROWTH_CONVERGED = Pattern.compile(
            "verify_growth: ([a-zA-Z_]+) in [a-z]+ coords ~ [+-]?[0-9.]+ over [0-9]+ window\\(s\\) of size "
                    + "[0-9]+ starting at ([0-9]+)\\. CONVERGED to growth constant ([+-]?[0-9.]+): "
                    + "mean rel-dev ([0-9.]+)");
    private static final Pattern OSC_ENVELOPE = Pattern.compile(
            "verify_oscillation: ([a-zA-Z_]+) over [0-9]+ window\\(s\\) of size [0-9]+ starting at ([0-9]+):"
                    // [0-9]+\.[0-9]+ (not [0-9.]+) so the trailing sentence period isn't grabbed into the value.
                    + ".*?OSCILLATES.*?envelope max\\|[a-zA-Z_]+\\|/[a-zA-Z_]+ = ([0-9]+\\.[0-9]+)");

    private final ConjectureRepository repo;

    @Value("${app.research.investigation-write-back.enabled:false}")
    private boolean enabled;

    public ConjectureInvestigationWriteBackService(ConjectureRepository repo) {
        this.repo = repo;
    }

    public boolean enabled() { return enabled; }

    /** Result-frame kinds for measured-invariant survivors (lever D). */
    public static final String KIND_CONSTANT = "CONSTANT";
    public static final String KIND_OSCILLATION = "OSCILLATION";

    /** One HELD bound parsed from an investigation trace. {@code relSlack} is the binding-window
     *  relative slack ({@code |margin|/|bound|}) if the line carried it, else {@code null}. */
    public record HeldBound(String lhs, String rhs, double k, double c,
                            int numWindows, long windowStart, double worstMargin, Double relSlack) {}

    /** One measured-invariant survivor parsed from a frame-1/2/3 result line: a CONSTANT limit
     *  ({@code lhs → value}) or an OSCILLATION envelope ({@code max|lhs|/scale ≤ value}). */
    public record MeasuredInvariant(String kind, String lhs, double value,
                                    long windowStart, double relativeSpread) {}

    /**
     * Extract every distinct measured-invariant (frame 1-3) survivor from a professor trace: a
     * verify_constant / verify_growth CONVERGED line (→ CONSTANT) or a verify_oscillation OSCILLATES
     * line with an envelope (→ OSCILLATION). Static + pure for testing without a repository.
     */
    public static List<MeasuredInvariant> parseMeasured(List<ToolInvocation> trace) {
        List<MeasuredInvariant> out = new ArrayList<>();
        if (trace == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ToolInvocation inv : trace) {
            if (inv == null || inv.resultJson() == null) continue;
            String r = inv.resultJson();
            collect(out, seen, CONST_CONVERGED.matcher(r), KIND_CONSTANT, /*spreadGroup*/4);
            collect(out, seen, GROWTH_CONVERGED.matcher(r), KIND_CONSTANT, 4);
            collect(out, seen, OSC_ENVELOPE.matcher(r), KIND_OSCILLATION, /*no spread*/-1);
        }
        return out;
    }

    private static void collect(List<MeasuredInvariant> out, Set<String> seen, Matcher m,
                                String kind, int spreadGroup) {
        while (m.find()) {
            try {
                String lhs = m.group(1);
                long start = Long.parseLong(m.group(2));
                double value = Double.parseDouble(m.group(3));
                double spread = spreadGroup > 0 ? Double.parseDouble(m.group(spreadGroup)) : 0.0;
                // Keep every distinct (kind,lhs,window) — the write-back picks the DEEPEST-x measurement
                // per relation (mirrors lever A): the professor often measures the same invariant at
                // several windows, and a low-x one must not shadow a failure-region one.
                if (seen.add(kind + "|" + lhs + "|" + start)) {
                    out.add(new MeasuredInvariant(kind, lhs, value, start, spread));
                }
            } catch (NumberFormatException ignore) { /* skip malformed */ }
        }
    }

    /** A measured invariant is worth persisting when it was measured AT the failure region (high x) —
     *  an easy low-x measurement of a slowly-converging quantity proves little. */
    static boolean measuredWritable(MeasuredInvariant m) {
        return m.windowStart() >= FAILURE_REGION_MIN_X;
    }

    /**
     * Extract every distinct HELD verify_conjecture bound from a professor trace.
     * Static + pure for testing without a repository.
     */
    public static List<HeldBound> parseHeldBounds(List<ToolInvocation> trace) {
        List<HeldBound> out = new ArrayList<>();
        if (trace == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (ToolInvocation inv : trace) {
            if (inv == null || inv.resultJson() == null) continue;
            Matcher m = HELD.matcher(inv.resultJson());
            while (m.find()) {
                try {
                    String lhs = m.group(1), rhs = m.group(3);
                    double k = Double.parseDouble(m.group(2));
                    double c = Double.parseDouble(m.group(4));
                    int nw = Integer.parseInt(m.group(5));
                    long start = Long.parseLong(m.group(6));
                    double margin = Double.parseDouble(m.group(7));
                    Double relSlack = m.group(8) == null ? null : Double.valueOf(m.group(8));
                    String key = lhs + "|" + rhs + "|" + k + "|" + c + "|" + start;
                    if (seen.add(key)) out.add(new HeldBound(lhs, rhs, k, c, nw, start, margin, relSlack));
                } catch (NumberFormatException ignore) { /* skip malformed */ }
            }
        }
        return out;
    }

    /** True when the bound is worth writing back: held at the failure region, with adversarial
     *  coverage, TIGHT enough at its binding window (lever B), and a legal (non-window-geometry-
     *  subject) inequality. A bound with no parsed relative-slack figure passes the tightness check
     *  (lenient — older/non-emitting lines). */
    static boolean writable(HeldBound b) {
        return rejectReason(b).isEmpty();
    }

    /** The first criterion barring this bound from write-back, or "" if writable. Diagnostic twin of
     *  {@link #writable} — its reason string is logged per bound so a non-write names the failing gate. */
    static String rejectReason(HeldBound b) {
        if (b.windowStart() < FAILURE_REGION_MIN_X) return "sub-failure-region(x=" + b.windowStart() + ")";
        if (b.numWindows() < MIN_WINDOWS) return "too-few-windows(" + b.numWindows() + ")";
        if (b.relSlack() != null && b.relSlack() > MAX_REL_SLACK) return "too-loose(rel-slack=" + b.relSlack() + ")";
        return ConjectureTrivialityFilter.dropReason(
                new Inequality(b.lhs(), b.rhs(), b.k(), b.c()))
                .map(r -> "trivial(" + r + ")").orElse("");
    }

    /** Lever A — among several HELD verifications of the SAME relation, the tightest: smallest parsed
     *  relative slack, tiebroken by the worst margin closest to 0 (bounds with a parsed slack rank
     *  ahead of those without). Used so the pool keeps the professor's tight fit, not whichever
     *  verify call happened to appear first in the trace (typically the loose probe). */
    static HeldBound tighter(HeldBound a, HeldBound b) {
        double sa = a.relSlack() == null ? Double.POSITIVE_INFINITY : a.relSlack();
        double sb = b.relSlack() == null ? Double.POSITIVE_INFINITY : b.relSlack();
        if (sa != sb) return sa < sb ? a : b;
        return a.worstMargin() >= b.worstMargin() ? a : b;   // held margins are <=0; closer to 0 = tighter
    }

    /** Backward-compatible overload (no paperId) — used by tests/non-paper callers. */
    public int writeBack(List<ToolInvocation> trace) {
        return writeBack(trace, "?");
    }

    /**
     * Write back every writable HELD bound from the trace. Returns the count written. No-op (returns 0)
     * when the flag is OFF. {@code paperId} appears in every funnel log so a capture miss names the paper.
     */
    @Transactional
    public int writeBack(List<ToolInvocation> trace, String paperId) {
        if (!enabled) return 0;
        int traceSize = trace == null ? 0 : trace.size();
        // Diagnostic counters — separate "trace lacked the verify outputs" from "regex missed HELD
        // summaries" from "writability gate rejected them". verifyCalls = tool results that ARE verify
        // outputs; heldSummaries = those whose summary says HELD; parsed = those the regex turned into a
        // HeldBound. heldSummaries >> parsed ⇒ a format/regex miss; verifyCalls small ⇒ trace truncation.
        int verifyCalls = 0, heldSummaries = 0;
        if (trace != null) {
            for (ToolInvocation inv : trace) {
                if (inv == null || inv.resultJson() == null) continue;
                if (inv.resultJson().contains("verify_conjecture:")) verifyCalls++;
                if (inv.resultJson().contains(" HELD across ")) heldSummaries++;
            }
        }
        List<HeldBound> bounds = parseHeldBounds(trace);
        List<MeasuredInvariant> measured = parseMeasured(trace);   // lever D — frames 1-3 survivors
        // Lever F observability — ALWAYS log the funnel, including the parsed=0 case (previously a silent
        // early return). This distinguishes "hook never fired for this paper" (no log at all) from "hook
        // fired but the union held no parseable HELD bound" (this line).
        if (bounds.isEmpty() && measured.isEmpty()) {
            log.info("investigation-write-back: paperId={} received {} tool-call(s) "
                    + "({} verify, {} with HELD-summary), parsed 0 HELD bound(s), 0 measured invariant(s)",
                    paperId, traceSize, verifyCalls, heldSummaries);
            return 0;
        }
        // Per-bound diagnosis: log every parsed bound and the exact gate that bars it (or WRITE).
        for (HeldBound b : bounds) {
            String reason = rejectReason(b);
            log.info("investigation-write-back: paperId={} bound {} <= {}*{} + {} @{} (windows={}, rel-slack={}) -> {}",
                    paperId, b.lhs(), b.k(), b.rhs(), b.c(), b.windowStart(), b.numWindows(),
                    b.relSlack(), reason.isEmpty() ? "WRITABLE" : "reject:" + reason);
        }
        // Lever A — collapse to the TIGHTEST writable bound per relation (not the first in trace).
        Map<String, HeldBound> tightestByRelation = new LinkedHashMap<>();
        for (HeldBound b : bounds) {
            if (!writable(b)) continue;
            tightestByRelation.merge(b.lhs() + "|" + b.rhs(), b,
                    ConjectureInvestigationWriteBackService::tighter);
        }
        Instant now = Instant.now();
        int written = 0;
        for (HeldBound b : tightestByRelation.values()) {
            try {
                written += upsert(b, now) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("investigation-write-back: upsert failed for {} <= {} ({})",
                        b.lhs(), b.rhs(), e.getMessage());
            }
        }
        if (written > 0) {
            log.info("investigation-write-back: paperId={} wrote {} HELD bound(s) into the conjecture pool "
                    + "(received {} tool-call(s): {} verify, {} HELD-summary, {} parsed, {} writable relation(s))",
                    paperId, written, traceSize, verifyCalls, heldSummaries, bounds.size(),
                    tightestByRelation.size());
        } else if (!bounds.isEmpty()) {
            log.info("investigation-write-back: paperId={} wrote 0 bound(s) — received {} tool-call(s): {} verify, "
                    + "{} HELD-summary, {} parsed, 0 writable (failure-region x>={}, >={} windows, rel-slack<={})",
                    paperId, traceSize, verifyCalls, heldSummaries, bounds.size(),
                    FAILURE_REGION_MIN_X, MIN_WINDOWS, MAX_REL_SLACK);
        }
        // Lever D — persist measured-invariant survivors (frames 1-3): a CONVERGED constant or an
        // OSCILLATES envelope at the failure region becomes a kind-tagged pool survivor the miner won't
        // re-test as a bound. Collapse to the DEEPEST-x measurement per (kind,lhs) — the professor often
        // measures the same invariant at several windows; a low-x one must not shadow a failure-region one
        // (the first-not-best bug lever A fixed for bounds). Decision A then keeps it live.
        Map<String, MeasuredInvariant> deepestByRelation = new LinkedHashMap<>();
        for (MeasuredInvariant mi : measured) {
            deepestByRelation.merge(mi.kind() + "|" + mi.lhs(), mi,
                    (a, b) -> a.windowStart() >= b.windowStart() ? a : b);
        }
        for (MeasuredInvariant mi : deepestByRelation.values()) {
            boolean ok = measuredWritable(mi);
            log.info("investigation-write-back: paperId={} measured {} {} = {} @{} (deepest of relation) -> {}",
                    paperId, mi.kind(), mi.lhs(), mi.value(), mi.windowStart(),
                    ok ? "PERSIST" : "reject:sub-failure-region(x=" + mi.windowStart() + ")");
            if (!ok) continue;
            try {
                written += upsertMeasured(mi, now) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("investigation-write-back: measured upsert failed for {} {} ({})",
                        mi.kind(), mi.lhs(), e.getMessage());
            }
        }
        return written;
    }

    /** Upsert a measured-invariant survivor as a kind-tagged conjecture row: a CONSTANT stores the limit
     *  as {@code (k=0, c=value)}; an OSCILLATION stores the envelope as {@code c}. Stamped
     *  investigationVerifiedAt so decision A protects it; rhs is a sentinel naming the frame. The miner
     *  skips non-BOUND rows, so a k=0 measurement is never counterexample-tested as a one-sided bound. */
    private boolean upsertMeasured(MeasuredInvariant mi, Instant now) {
        String rhs = "_" + mi.kind().toLowerCase() + "_";   // sentinel: not a real rhs scalar
        Optional<Conjecture> ex = repo.findFirstByDomainAndLhsAndRhs(DOMAIN, mi.lhs(), rhs);
        Conjecture row;
        if (ex.isPresent()) {
            row = ex.get();
            row.setC(mi.value());
            if (row.confirmedRuns() < 1) row.setConfirmedRuns(1);
            row.setDroppedByKnob(null);
            row.setKind(mi.kind());
            row.setUpdatedAt(now);
            repo.save(row);
            log.info("investigation-write-back: {} {} = {} @{} — measured-invariant survivor updated",
                    mi.kind(), mi.lhs(), mi.value(), mi.windowStart());
        } else {
            row = new Conjecture(DOMAIN, mi.lhs(), rhs, 0.0, mi.value());
            row.setKind(mi.kind());
            row.setConfirmedRuns(1);
            row.setUpdatedAt(now);
            row = repo.save(row);
            log.info("investigation-write-back: {} {} = {} @{} — measured-invariant survivor inserted",
                    mi.kind(), mi.lhs(), mi.value(), mi.windowStart());
        }
        repo.stampInvestigationVerified(row.id(), now);
        return true;
    }

    private boolean upsert(HeldBound b, Instant now) {
        Optional<Conjecture> ex = repo.findFirstByDomainAndLhsAndRhs(DOMAIN, b.lhs(), b.rhs());
        Conjecture row;
        if (ex.isPresent()) {
            row = ex.get();
            String wasState = row.droppedByKnob() == null ? "live" : "retired:" + row.droppedByKnob();
            row.setK(b.k());
            row.setC(b.c());
            if (row.confirmedRuns() < 1) row.setConfirmedRuns(1);
            row.setDroppedByKnob(null);   // revive: the professor proved it holds
            row.setUpdatedAt(now);
            repo.save(row);
            log.info("investigation-write-back: {} <= {}*{} + {} HELD@{}{} — revived from {} "
                            + "with professor fit (was {})",
                    b.lhs(), b.k(), b.rhs(), b.c(), b.windowStart(), tightnessTag(b), wasState,
                    String.format("k≈%.2f", row.k()));
        } else {
            row = new Conjecture(DOMAIN, b.lhs(), b.rhs(), b.k(), b.c());
            row.setConfirmedRuns(1);
            row.setUpdatedAt(now);
            row = repo.save(row);
            log.info("investigation-write-back: {} <= {}*{} + {} HELD@{}{} — inserted new live relation",
                    b.lhs(), b.k(), b.rhs(), b.c(), b.windowStart(), tightnessTag(b));
        }
        repo.stampInvestigationVerified(row.id(), now);
        return true;
    }

    private static String tightnessTag(HeldBound b) {
        return b.relSlack() == null ? "" : String.format(" (rel-slack %.4f)", b.relSlack());
    }
}
