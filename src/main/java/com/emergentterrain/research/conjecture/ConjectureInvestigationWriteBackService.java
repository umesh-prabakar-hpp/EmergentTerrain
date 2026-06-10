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

    private final ConjectureRepository repo;

    @Value("${app.research.investigation-write-back.enabled:false}")
    private boolean enabled;

    public ConjectureInvestigationWriteBackService(ConjectureRepository repo) {
        this.repo = repo;
    }

    public boolean enabled() { return enabled; }

    /** One HELD bound parsed from an investigation trace. {@code relSlack} is the binding-window
     *  relative slack ({@code |margin|/|bound|}) if the line carried it, else {@code null}. */
    public record HeldBound(String lhs, String rhs, double k, double c,
                            int numWindows, long windowStart, double worstMargin, Double relSlack) {}

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
        if (b.windowStart() < FAILURE_REGION_MIN_X) return false;
        if (b.numWindows() < MIN_WINDOWS) return false;
        if (b.relSlack() != null && b.relSlack() > MAX_REL_SLACK) return false;   // lever B: no loose survivors
        return ConjectureTrivialityFilter.dropReason(
                new Inequality(b.lhs(), b.rhs(), b.k(), b.c())).isEmpty();
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
        List<HeldBound> bounds = parseHeldBounds(trace);
        // Lever F observability — ALWAYS log the funnel, including the parsed=0 case (previously a silent
        // early return). This distinguishes "hook never fired for this paper" (no log at all) from "hook
        // fired but the union held no HELD bound" (this line), which cost a session to tell apart.
        if (bounds.isEmpty()) {
            log.info("investigation-write-back: paperId={} received {} tool-call(s), parsed 0 HELD bound(s)",
                    paperId, traceSize);
            return 0;
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
                    + "(received {} tool-call(s), parsed {} HELD, {} writable relation(s))",
                    paperId, written, traceSize, bounds.size(), tightestByRelation.size());
        } else {
            log.info("investigation-write-back: paperId={} parsed {} HELD bound(s) (of {} tool-call(s)) "
                    + "but wrote 0 — none writable (failure-region x>={}, >={} windows, rel-slack<={}, non-trivial)",
                    paperId, bounds.size(), traceSize, FAILURE_REGION_MIN_X, MIN_WINDOWS, MAX_REL_SLACK);
        }
        return written;
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
