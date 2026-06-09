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
import java.util.LinkedHashSet;
import java.util.List;
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

    /** Parses a verify_conjecture result line (MathToolSuite format). */
    private static final Pattern HELD = Pattern.compile(
            "verify_conjecture: ([a-zA-Z_]+) <= ([+-][0-9.]+) \\* ([a-zA-Z_]+) \\+ "
                    + "([+-][0-9.]+) HELD across ([0-9]+) window\\(s\\) of size [0-9]+ "
                    + "starting at ([0-9]+)\\. worst margin: ([+-][0-9.]+)");

    private final ConjectureRepository repo;

    @Value("${app.research.investigation-write-back.enabled:false}")
    private boolean enabled;

    public ConjectureInvestigationWriteBackService(ConjectureRepository repo) {
        this.repo = repo;
    }

    public boolean enabled() { return enabled; }

    /** One HELD bound parsed from an investigation trace. */
    public record HeldBound(String lhs, String rhs, double k, double c,
                            int numWindows, long windowStart, double worstMargin) {}

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
                    String key = lhs + "|" + rhs + "|" + k + "|" + c + "|" + start;
                    if (seen.add(key)) out.add(new HeldBound(lhs, rhs, k, c, nw, start, margin));
                } catch (NumberFormatException ignore) { /* skip malformed */ }
            }
        }
        return out;
    }

    /** True when the bound is worth writing back: held at the failure region, with
     *  adversarial coverage, and a legal (non-window-geometry-subject) inequality. */
    static boolean writable(HeldBound b) {
        if (b.windowStart() < FAILURE_REGION_MIN_X) return false;
        if (b.numWindows() < MIN_WINDOWS) return false;
        return ConjectureTrivialityFilter.dropReason(
                new Inequality(b.lhs(), b.rhs(), b.k(), b.c())).isEmpty();
    }

    /**
     * Write back every writable HELD bound from the trace. Returns the count
     * written. No-op (returns 0) when the flag is OFF or the trace has none.
     */
    @Transactional
    public int writeBack(List<ToolInvocation> trace) {
        if (!enabled) return 0;
        List<HeldBound> bounds = parseHeldBounds(trace);
        if (bounds.isEmpty()) return 0;
        Instant now = Instant.now();
        int written = 0;
        Set<String> done = new LinkedHashSet<>();
        for (HeldBound b : bounds) {
            if (!writable(b)) continue;
            if (!done.add(b.lhs() + "|" + b.rhs())) continue;   // one upsert per relation
            try {
                written += upsert(b, now) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("investigation-write-back: upsert failed for {} <= {} ({})",
                        b.lhs(), b.rhs(), e.getMessage());
            }
        }
        if (written > 0) {
            log.info("investigation-write-back: wrote {} HELD bound(s) into the conjecture pool",
                    written);
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
            log.info("investigation-write-back: {} <= {}*{} + {} HELD@{} — revived from {} "
                            + "with professor fit (was {})",
                    b.lhs(), b.k(), b.rhs(), b.c(), b.windowStart(), wasState,
                    String.format("k≈%.2f", row.k()));
        } else {
            row = new Conjecture(DOMAIN, b.lhs(), b.rhs(), b.k(), b.c());
            row.setConfirmedRuns(1);
            row.setUpdatedAt(now);
            row = repo.save(row);
            log.info("investigation-write-back: {} <= {}*{} + {} HELD@{} — inserted new live relation",
                    b.lhs(), b.k(), b.rhs(), b.c(), b.windowStart());
        }
        repo.stampInvestigationVerified(row.id(), now);
        return true;
    }
}
