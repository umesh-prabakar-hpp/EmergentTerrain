package com.emergentterrain.research;

import com.emergentterrain.government.BotCivicState;

import java.util.List;

/**
 * Phase 1 Iter 2 — a read-only view over a bot's research personality. Built by
 * {@link #from(BotLifecycleState, BotCivicState, List)} from existing sources;
 * no new persistent state is introduced beyond {@link BotLifecycleState#getSkepticismLevel}
 * and the {@link ReputationHistory} table.
 *
 * Methodology is a {@code String} (not an enum) because {@link BotGenome} stores
 * it as one of the values from {@code BotGenome.METHODOLOGIES}. Promoting it to
 * an enum is a separate, optional refactor.
 *
 * Supplying a null civicState produces an identity with a neutral
 * ideology vector — useful in tests and rooms where government is disabled.
 */
public record ResearchIdentity(
        String              favoredMethod,
        double              riskTolerance,
        double              skepticismLevel,
        double[]            topicAffinity,
        double[]            ideologyVector,
        double              reputation,
        int                 publishedCount,
        int                 rejectedCount,
        List<ReputationHistory> reputationHistory
) {

    /** Build a snapshot of the bot's identity without persisting anything. */
    public static ResearchIdentity from(BotLifecycleState lifecycle,
                                         BotCivicState civicState,
                                         List<ReputationHistory> history) {
        BotGenome g = lifecycle.getGenome();
        double[] weights = g != null ? g.getTopicWeights().clone() : new double[]{0, 0, 0, 0, 0};
        String   method  = g != null ? g.getMethodology() : "THEORETICAL";
        double   risk    = g != null ? g.getExplorationRate() : 0.2;

        double[] ideology = civicState != null
                ? new double[]{
                        civicState.getIdeologyLiberty(),
                        civicState.getIdeologyOrder(),
                        civicState.getIdeologyWelfare(),
                        civicState.getIdeologyGrowth()}
                : new double[]{0.5, 0.5, 0.5, 0.5};

        return new ResearchIdentity(
                method,
                risk,
                lifecycle.getSkepticismLevel(),
                weights,
                ideology,
                lifecycle.getReputation(),
                lifecycle.getPublishedCount(),
                lifecycle.getRejectedCount(),
                history != null ? List.copyOf(history) : List.of()
        );
    }

    /**
     * Strict-tolerance multiplier in [0.5, 1.0] derived from skepticismLevel.
     * Used by the peer-review path in {@link ResearchCycleService} so a
     * skeptical reviewer applies a tighter agreement window than a lenient one.
     *
     * skepticismLevel=0.0 → 1.0 (no change), skepticismLevel=1.0 → 0.5 (twice as strict).
     */
    public double effectivePeerToleranceMultiplier() {
        return 1.0 - Math.max(0.0, Math.min(1.0, skepticismLevel)) * 0.5;
    }
}
