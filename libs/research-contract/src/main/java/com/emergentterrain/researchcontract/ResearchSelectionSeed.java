package com.emergentterrain.researchcontract;

import java.util.UUID;

/**
 * Stable seed derivation for a frozen research-selection cycle.
 *
 * <p>The caller's source revision is deliberately opaque to this contract. Both the legacy
 * compatibility cycle and the game-to-research mapper use this calculation so author election,
 * queue probability, and catalog topic draws retain their existing cadence during extraction.
 */
public final class ResearchSelectionSeed {
    private ResearchSelectionSeed() { }

    public static long from(UUID workspaceId, long sourceRevision) {
        if (workspaceId == null) {
            throw new IllegalArgumentException("A selection seed requires a workspace id");
        }
        return sourceRevision * 1337L + workspaceId.getLeastSignificantBits();
    }
}
