package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4c Piece 3 PR4 — scheduled rollback judge. Runs a one-sided
 * two-proportion z-test on each ACTIVE non-incumbent variant against
 * the incumbent (the ACTIVE variant with the most {@code papersNarrated}
 * for the template id). When the challenger's replication-failure rate
 * is significantly worse than the incumbent's AND the absolute
 * difference exceeds the configured MDE, status flips to
 * {@link PromptTemplateVariant.Status#ROLLED_BACK} and persists.
 *
 * <p>Locked thresholds (per the piece-3 design doc Decision 7/8
 * revisions):
 * <ul>
 *   <li>Minimum {@code papersNarrated} per arm before the test runs:
 *       <b>200</b> (was 1000 in the original memo — cut by 5× to match
 *       the observed ~38 papers/day corpus rate, with MDE relaxed
 *       to 10pp as the algebraic consequence).</li>
 *   <li>α = 0.01 one-sided. The multiple-comparison cushion the memo
 *       called out is preserved.</li>
 *   <li>MDE = 0.10 absolute. A challenger 10pp worse than the
 *       incumbent gets rolled back; 5pp worse stays.</li>
 *   <li>Aggregate across domains — Decision 10 revision dropped the
 *       per-domain isolation because per-domain × per-variant exceeds
 *       the corpus budget at current rates.</li>
 * </ul>
 *
 * <p>Flag-gated: {@code app.research.prompt-template-rollback.enabled}
 * defaults to false. Operator activates when the stats writer (PR3)
 * has accumulated ≥ 200 samples on at least one challenger and the
 * revised decisions are accepted.
 */
@Service
public class PromptTemplateRollbackJudge {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRollbackJudge.class);

    /** Minimum terminal outcomes per arm before the z-test fires. */
    static final long MIN_TERMINAL_OUTCOMES_PER_ARM = 200L;

    /** Absolute failure-rate margin a challenger must exceed to roll back. */
    static final double MIN_DETECTABLE_EFFECT = 0.10;

    /** One-sided z critical value for α = 0.01 (rounds to 4 sig figs). */
    static final double Z_CRITICAL_ALPHA_001_ONE_SIDED = 2.3263;

    private final PromptTemplateVariantRepository repo;

    @Value("${app.research.prompt-template-rollback.enabled:false}")
    private boolean enabled;

    public PromptTemplateRollbackJudge(PromptTemplateVariantRepository repo) {
        this.repo = repo;
    }

    /**
     * Scheduled at the same 5-minute cadence as the dispute-orphan scan
     * (Phase 21 §21.2). Each run iterates ACTIVE variants grouped by
     * template id; the highest-{@code papersNarrated} arm in each group
     * is the incumbent and is never rolled back by this job. Operator
     * action is required to roll back an incumbent.
     *
     * <p>No-op when the flag is off. Defensive try-catch around the
     * batch so a single bad row doesn't poison the whole sweep.
     */
    @Scheduled(fixedDelayString = "${app.research.prompt-template-rollback-scan-ms:300000}")
    public void scanForRollbacks() {
        if (!enabled) return;
        try {
            sweep();
        } catch (Exception e) {
            log.warn("PromptTemplateRollbackJudge: sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Single sweep pass. Package-private so tests can invoke it
     * directly without waiting for the scheduler.
     */
    int sweep() {
        List<PromptTemplateVariant> active = repo.findAll().stream()
                .filter(v -> v.getStatus() == PromptTemplateVariant.Status.ACTIVE)
                .toList();
        if (active.isEmpty()) return 0;

        Map<String, List<PromptTemplateVariant>> byId = new HashMap<>();
        for (PromptTemplateVariant v : active) {
            byId.computeIfAbsent(v.getTemplateId(), k -> new ArrayList<>()).add(v);
        }
        int rolledBack = 0;
        for (Map.Entry<String, List<PromptTemplateVariant>> e : byId.entrySet()) {
            List<PromptTemplateVariant> arms = e.getValue();
            if (arms.size() < 2) continue;

            // Pick the incumbent: highest papersNarrated.
            PromptTemplateVariant incumbent = arms.get(0);
            for (int i = 1; i < arms.size(); i++) {
                if (arms.get(i).getPapersNarrated() > incumbent.getPapersNarrated()) {
                    incumbent = arms.get(i);
                }
            }
            for (PromptTemplateVariant challenger : arms) {
                if (challenger == incumbent) continue;
                if (shouldRollBack(challenger, incumbent)) {
                    challenger.setStatus(PromptTemplateVariant.Status.ROLLED_BACK);
                    repo.save(challenger);
                    rolledBack++;
                    log.warn("PromptTemplateRollbackJudge: variant=({}, {}) ROLLED_BACK vs incumbent ({}, {}). "
                                    + "challenger n={} fail_rate={}, incumbent n={} fail_rate={}",
                            challenger.getTemplateId(), challenger.getVersionTag(),
                            incumbent.getTemplateId(), incumbent.getVersionTag(),
                            terminalCount(challenger), String.format("%.3f", failureRate(challenger)),
                            terminalCount(incumbent),  String.format("%.3f", failureRate(incumbent)));
                }
            }
        }
        if (rolledBack > 0) {
            log.info("PromptTemplateRollbackJudge: sweep rolled-back {} variant(s)", rolledBack);
        }
        return rolledBack;
    }

    /**
     * One-sided two-proportion z-test plus an absolute MDE check.
     * Returns true when both conditions hold:
     * <ol>
     *   <li>Both arms have ≥ {@link #MIN_TERMINAL_OUTCOMES_PER_ARM}
     *       terminal outcomes.</li>
     *   <li>{@code z ≥ Z_CRITICAL_ALPHA_001_ONE_SIDED} (challenger
     *       failure rate is significantly worse than incumbent).</li>
     *   <li>{@code (p_challenger - p_incumbent) ≥ MIN_DETECTABLE_EFFECT}
     *       (the difference is meaningfully large, not just
     *       statistically detectable on a huge sample).</li>
     * </ol>
     *
     * <p>One-sided because rolling back when a variant is BETTER would
     * be backwards. Symmetric statistical power for that direction is
     * irrelevant — promotion of a winner is operator action, not
     * scheduler action.
     */
    static boolean shouldRollBack(PromptTemplateVariant challenger,
                                   PromptTemplateVariant incumbent) {
        long nC = terminalCount(challenger);
        long nI = terminalCount(incumbent);
        if (nC < MIN_TERMINAL_OUTCOMES_PER_ARM) return false;
        if (nI < MIN_TERMINAL_OUTCOMES_PER_ARM) return false;
        double pC = failureRate(challenger);
        double pI = failureRate(incumbent);
        if (pC - pI < MIN_DETECTABLE_EFFECT) return false;
        // Pooled standard error.
        long totalFail = challenger.getReplicationsFailed() + incumbent.getReplicationsFailed();
        long totalN    = nC + nI;
        double pPool = (double) totalFail / (double) totalN;
        double se = Math.sqrt(pPool * (1.0 - pPool) * (1.0 / nC + 1.0 / nI));
        if (se <= 0.0) return false;
        double z = (pC - pI) / se;
        return z >= Z_CRITICAL_ALPHA_001_ONE_SIDED;
    }

    private static long terminalCount(PromptTemplateVariant v) {
        return v.getReplicationsConfirmed() + v.getReplicationsFailed();
    }

    private static double failureRate(PromptTemplateVariant v) {
        long n = terminalCount(v);
        return n <= 0 ? 0.0 : (double) v.getReplicationsFailed() / (double) n;
    }
}
