package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.FrontierKnowledgeStore.ConvergenceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 30 fork 2b — accumulates frontier knowledge across runs (8b): persists each
 * run's validated checkpoints with **keep-the-best** (highest-trust value per
 * {@code (bound, x)}), **ratchets** the frontier, and detects **convergence** so the
 * loop stops re-spending once results stabilize. Persisted via {@link
 * FrontierKnowledgeStore}, so it survives restart and the corpus is reusable by
 * future papers (trust carried verbatim — never laundered).
 */
@Service
public class FrontierKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(FrontierKnowledgeService.class);

    /** Outcome of recording a run. */
    public record RunRecord(int recordsKept, long frontier, int stableRuns, boolean converged, int methodsKept) {
        public RunRecord(int recordsKept, long frontier, int stableRuns, boolean converged) {
            this(recordsKept, frontier, stableRuns, converged, 0);
        }
    }

    private final FrontierKnowledgeStore store;
    private final int convergenceThreshold;
    private final boolean recallEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public FrontierKnowledgeService(
            FrontierKnowledgeStore store,
            @Value("${app.research.discovery.convergenceThreshold:2}") int convergenceThreshold,
            @Value("${app.research.discovery.frontierRecallEnabled:false}") boolean recallEnabled) {
        this.store = store;
        this.convergenceThreshold = Math.max(1, convergenceThreshold);
        this.recallEnabled = recallEnabled;
    }

    /** Back-compatible constructor (recall defaults OFF) for tests/call sites. */
    public FrontierKnowledgeService(FrontierKnowledgeStore store, int convergenceThreshold) {
        this(store, convergenceThreshold, false);
    }

    public boolean isRecallEnabled() {
        return recallEnabled;
    }

    public boolean isConverged(String bound) {
        return store.convergence(bound).converged();
    }

    public long frontier(String bound) {
        return store.frontierOf(bound);
    }

    /**
     * Persist a run's validated checkpoints (keep-the-best) and update convergence.
     * A run that didn't run (disabled/skipped) is ignored. Returns the new state.
     */
    public RunRecord record(String bound, EscalationOutcome outcome) {
        ConvergenceState prev = store.convergence(bound);
        if (outcome == null || !outcome.ran()) {
            return new RunRecord(0, prev.frontier(), prev.stableRuns(), prev.converged());
        }
        int kept = 0;
        int methodsKept = 0;
        for (EscalationCheckpoint c : outcome.checkpoints()) {
            if (c.verdict() == EscalationVerdict.HOLDS && c.value() != null) {
                // every validated rung is FULL trust (sieve exact / discovery moat-validated)
                if (keepBest(new FrontierRecord(bound, c.x(), c.value(), c.source(), TrustLevel.FULL))) {
                    kept++;
                }
                // a discovery rung carries the LLM-generated, moat-validated method that
                // reached x — persist it into the reusable library (keep-the-best by reach).
                if (c.methodCode() != null && !c.methodCode().isBlank()
                        && keepBestMethod(bound, c.methodCode(), c.x(), c.elapsedMs())) {
                    methodsKept++;
                }
            }
        }
        boolean progressed = outcome.frontierReached() > prev.frontier();
        long frontier = Math.max(prev.frontier(), outcome.frontierReached());
        int stable = progressed ? 0 : prev.stableRuns() + 1;
        boolean converged = stable >= convergenceThreshold;
        store.saveConvergence(bound, new ConvergenceState(frontier, stable, converged));
        log.info("frontier knowledge [{}] — kept {} record(s), {} method(s); frontier={}; progressed={}; stableRuns={}/{}; converged={}",
                bound, kept, methodsKept, frontier, progressed, stable, convergenceThreshold, converged);
        return new RunRecord(kept, frontier, stable, converged, methodsKept);
    }

    /** Put the record unless an equal-or-higher-trust value is already stored at (bound, x). */
    private boolean keepBest(FrontierRecord rec) {
        var existing = store.find(rec.bound(), rec.x());
        if (existing.isPresent() && existing.get().trust().ordinal() <= rec.trust().ordinal()) {
            return false;   // keep the existing (same or better trust)
        }
        store.put(rec);
        return true;
    }

    /**
     * Persist a method into the library, keeping the best by reach. The method is keyed
     * by {@code (bound, sha256(code))}: a re-validated identical method only updates its
     * reach/cost when it got further/cheaper; distinct methods coexist (the library is
     * ranked across them by {@link FrontierKnowledgeStore#bestMethod}).
     */
    private boolean keepBestMethod(String bound, String code, long reach, long costMs) {
        String sha = sha256(code);
        var existing = store.findMethod(bound, sha);
        if (existing.isPresent()) {
            VerifiedMethod e = existing.get();
            if (reach <= e.maxScale()) {
                return false;   // already recorded at an equal-or-further reach
            }
        }
        store.putMethod(new VerifiedMethod(bound, sha, code, reach, costMs));
        return true;
    }

    /** The records and best method accumulated for a bound (for paper-side recall). */
    public record Recall(java.util.List<FrontierRecord> records, long frontier,
                         java.util.Optional<VerifiedMethod> bestMethod) {}

    public Recall recall(String bound) {
        return new Recall(store.recordsFor(bound), store.frontierOf(bound), store.bestMethod(bound));
    }

    /**
     * Render the accumulated knowledge for a paper, trust carried verbatim — never laundered.
     * Each line states the trust ceiling of its source so a reader (and the model) cannot
     * mistake a moat-validated discovery value for an unconditional proof.
     */
    public String recallText(String bound) {
        Recall r = recall(bound);
        if (r.records().isEmpty()) {
            return "No accumulated frontier knowledge for `" + bound + "` yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Accumulated, independently re-verified frontier knowledge for `").append(bound)
                .append("` (frontier x = ").append(r.frontier()).append("):\n\n");
        sb.append("| x | value | source | trust |\n|---|---|---|---|\n");
        for (FrontierRecord rec : r.records()) {
            sb.append("| ").append(rec.x()).append(" | ").append(rec.value())
                    .append(" | ").append(rec.source())
                    .append(" | ").append(rec.trust()).append(" |\n");
        }
        r.bestMethod().ifPresent(m -> sb.append("\nBest verified method reaches x = ").append(m.maxScale())
                .append(" (cost ").append(m.costMs()).append(" ms, sha256 ")
                .append(m.methodSha256(), 0, Math.min(12, m.methodSha256().length())).append("…).\n"));
        sb.append("\n_Trust is carried verbatim from the producing run; a `FULL`-trust value was exact-")
                .append("agreement-validated against a trusted oracle on its overlap range. This is verified ")
                .append("computation at the stated x, not a proof of the bound for all x._");
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
