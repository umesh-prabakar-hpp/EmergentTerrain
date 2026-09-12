package com.emergentterrain.research.quality;

import java.util.Collection;

/**
 * Phase 43 admission rule for an already-recorded exact protocol.
 *
 * <p>Ordinary reuse remains allowed for unassessed outputs, notes, and drafts. Only an AI
 * assessment that establishes human-quality-or-beyond settles the protocol for ordinary reuse.
 */
public final class QualityAwareProtocolReusePolicy {

    private QualityAwareProtocolReusePolicy() {}

    public static boolean blocksOrdinaryReuse(Collection<ResearchQualityGrade> priorGrades) {
        if (priorGrades == null) {
            return false;
        }
        return priorGrades.stream().filter(java.util.Objects::nonNull)
                .anyMatch(ResearchQualityGrade::settlesProtocol);
    }
}
