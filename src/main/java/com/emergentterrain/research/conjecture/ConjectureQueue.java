package com.emergentterrain.research.conjecture;

import java.util.List;
import java.util.Optional;

/**
 * Read-only queue surface over the mined conjecture store. Extracted
 * from {@link ConjectureMinerService} so callers that only need to
 * <em>consume</em> conjectures (the paper-writing pipeline) don't
 * depend on the full miner — which carries the SPF sieve and the
 * @Scheduled write path. Also makes the consumers testable without
 * mocking the concrete service class.
 *
 * <p>Phase 23.3 ordering is documented on the implementing methods.
 */
public interface ConjectureQueue {

    /** Highest-priority conjecture for a domain, or empty if the queue is dry. */
    Optional<Conjecture> next(String domain);

    /** Up to {@code n} highest-priority conjectures, in priority order. */
    List<Conjecture> nextN(String domain, int n);

    /**
     * Phase 30 Wave 0 — mark a conjecture as selected-for-a-paper (consumption),
     * so {@link #nextN} can rotate away from it instead of re-serving the same
     * refuted conjecture every cycle. Default no-op; implemented by the miner.
     */
    default void markInvestigated(Conjecture conjecture) {
        // no-op by default
    }
}
