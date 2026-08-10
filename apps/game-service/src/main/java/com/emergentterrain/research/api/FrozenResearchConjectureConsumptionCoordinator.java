package com.emergentterrain.research.api;

import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ConjectureTopicProvider;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Explicit, game-owned cutover seam for consuming an already-observed conjecture.
 *
 * <p>This is intentionally not called by the outbox tick gateway.  It does not observe, rank,
 * or select the live queue: its caller must provide both a frozen reserved submission and the
 * exact persistent conjecture that produced that frozen source id.  The reservation/outbox pair
 * is persisted before the one-time claim and source stamp, and a source-stamping failure rolls
 * all newly-created handoff state back.
 */
@Service
public class FrozenResearchConjectureConsumptionCoordinator {

    private static final String CONJECTURE_RESERVATION = "CONJECTURE";

    private final ResearchWorkOutboxService outbox;
    private final ResearchWorkSourceReservationRepository reservations;
    private final ConjectureTopicProvider conjectures;

    public FrozenResearchConjectureConsumptionCoordinator(ResearchWorkOutboxService outbox,
                                                           ResearchWorkSourceReservationRepository reservations,
                                                           ConjectureTopicProvider conjectures) {
        this.outbox = outbox;
        this.reservations = reservations;
        this.conjectures = conjectures;
    }

    /**
     * Durably accepts and consumes one exact frozen conjecture source.  An exact retry replays
     * the outbox validation but does not mark the source a second time.
     */
    @Transactional
    public void submitAndConsume(FrozenResearchWorkSubmission submission, Conjecture observedConjecture) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(observedConjecture, "observedConjecture");
        ResearchWorkSourceReservation reservation = submission.reservation();
        if (!CONJECTURE_RESERVATION.equals(reservation.reservationType())) {
            throw new IllegalArgumentException("Only conjecture reservations may cross the consumption seam");
        }
        if (observedConjecture.id() == null
                || !reservation.sourceId().equals(observedConjecture.id().toString())) {
            throw new IllegalArgumentException("Observed conjecture source id must match its frozen reservation");
        }

        outbox.enqueueReserved(submission.request(), reservation);
        int claimed = reservations.claimForConsumption(reservation.requestId(), reservation.reservationType(),
                reservation.sourceId(), Instant.now());
        if (claimed == 1) {
            conjectures.consumeObservedConjecture(observedConjecture);
        } else if (claimed != 0) {
            throw new IllegalStateException("Expected at most one conjecture source-consumption claim");
        }
    }
}
