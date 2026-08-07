package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkSourceReservationRepository;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Persists a work request in the same game transaction before any network delivery is attempted. */
@Service
public class ResearchWorkOutboxService {

    private final ResearchWorkOutboxRepository outbox;
    private final ResearchWorkSourceReservationRepository reservations;
    private final ObjectMapper objectMapper;

    public ResearchWorkOutboxService(ResearchWorkOutboxRepository outbox, ObjectMapper objectMapper) {
        this(outbox, null, objectMapper);
    }

    @Autowired
    public ResearchWorkOutboxService(ResearchWorkOutboxRepository outbox,
                                     ResearchWorkSourceReservationRepository reservations,
                                     ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.reservations = reservations;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(ResearchWorkRequest request) {
        try {
            outbox.insertIfAbsent(request.requestId(), request.workType(), objectMapper.writeValueAsString(request),
                    Instant.now());
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to serialize research work request", serializationFailure);
        }
    }

    /**
     * Persists game-owned selection metadata before the durable request in one transaction.
     * This records no queue mutation and is intentionally unavailable without its repository wiring.
     */
    @Transactional
    public void enqueueReserved(ResearchWorkRequest request, ResearchWorkSourceReservation reservation) {
        if (reservations == null) {
            throw new IllegalStateException("Research work source reservations are not configured");
        }
        if (!request.requestId().equals(reservation.requestId())) {
            throw new IllegalArgumentException("Reservation requestId must match the work request");
        }
        int inserted = reservations.insertIfAbsent(reservation.requestId(), reservation.reservationType(),
                reservation.sourceId(), Instant.now());
        if (inserted == 0) {
            var existing = reservations.findById(reservation.requestId())
                    .orElseThrow(() -> new IllegalStateException("Existing source reservation was not readable"));
            if (!reservation.reservationType().equals(existing.getReservationType())
                    || !reservation.sourceId().equals(existing.getSourceId())) {
                throw new IllegalStateException("A requestId cannot be rebound to a different research source");
            }
        }
        enqueue(request);
    }
}
