package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.EvidenceLabel;

import java.util.Collection;

/**
 * Phase 31 §31.7 — the weakest-source label cap for synthesis papers. A
 * synthesis cannot launder a {@code CONJECTURE} into an
 * {@code EMPIRICAL_RESULT} by aggregation: its evidence label is bounded by
 * the WEAKEST of the sources it cites. Pure, stateless.
 *
 * <p>The strength axis (strongest → weakest) is the {@link EvidenceLabel}
 * declaration order on the THEOREM…UNEXPLAINED_OBSERVATION spine:
 * {@code THEOREM > EMPIRICAL_RESULT > CONJECTURE > UNEXPLAINED_OBSERVATION}.
 * {@code KNOWN_RESULT} is an orthogonal rediscovery marker (not a strength
 * level) and is excluded from the cap — a synthesis citing a known result
 * alongside fresh work is bounded by the fresh work, not labelled a
 * rediscovery.
 */
public final class SynthesisLabelCap {

    private SynthesisLabelCap() {}

    /** Strength rank: lower = stronger. KNOWN_RESULT returns -1 (excluded). */
    static int strength(EvidenceLabel label) {
        if (label == null) return Integer.MAX_VALUE;
        return switch (label) {
            case THEOREM -> 0;
            case EMPIRICAL_RESULT -> 1;
            case CONJECTURE -> 2;
            case UNEXPLAINED_OBSERVATION -> 3;
            case KNOWN_RESULT -> -1;   // orthogonal — not a strength level
        };
    }

    /**
     * The strongest label a synthesis citing {@code sources} may carry: the
     * WEAKEST source on the strength spine (KNOWN_RESULT ignored). Empty or
     * all-KNOWN_RESULT input → {@code CONJECTURE} (the system's default,
     * matching {@code EvidenceLabel}'s own field default) — a synthesis with
     * no rankable evidence is at most a conjecture.
     */
    public static EvidenceLabel cap(Collection<EvidenceLabel> sources) {
        EvidenceLabel weakest = null;
        int weakestRank = -1;
        if (sources != null) {
            for (EvidenceLabel s : sources) {
                int r = strength(s);
                if (r < 0) continue;            // KNOWN_RESULT — skip
                if (r > weakestRank) {          // weaker = higher rank
                    weakestRank = r;
                    weakest = s;
                }
            }
        }
        return weakest == null ? EvidenceLabel.CONJECTURE : weakest;
    }

    /**
     * Apply the cap to a proposed label: the proposed label passes through
     * only if it is no stronger than the source cap; otherwise the cap wins.
     */
    public static EvidenceLabel applyCap(EvidenceLabel proposed,
                                         Collection<EvidenceLabel> sources) {
        EvidenceLabel cap = cap(sources);
        if (proposed == null) return cap;
        // KNOWN_RESULT proposal passes through (orthogonal marker).
        if (strength(proposed) < 0) return proposed;
        return strength(proposed) < strength(cap) ? cap : proposed;
    }
}
