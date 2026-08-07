package com.emergentterrain.researchcontract;

import java.util.Random;

/** Pure replay of promoted-first, then probability-gated ordered conjecture selection. */
public final class StandardResearchConjectureQueueSelector {
    private StandardResearchConjectureQueueSelector() { }

    public static ResearchConjectureQueueSelection select(ResearchConjectureQueueSnapshot snapshot, Random random) {
        if (!snapshot.promotedCandidates().isEmpty()) {
            return new ResearchConjectureQueueSelection(snapshot.promotedCandidates().getFirst(), 0);
        }
        if (snapshot.ordinarySelectionProbability() <= 0.0 || snapshot.ordinaryCandidates().isEmpty()) {
            return new ResearchConjectureQueueSelection(null, 0);
        }
        return random.nextDouble() < snapshot.ordinarySelectionProbability()
                ? new ResearchConjectureQueueSelection(snapshot.ordinaryCandidates().getFirst(), 1)
                : new ResearchConjectureQueueSelection(null, 1);
    }
}
