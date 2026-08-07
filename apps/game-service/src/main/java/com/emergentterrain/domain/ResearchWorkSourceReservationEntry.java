package com.emergentterrain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Game-owned, request-keyed reservation recorded atomically with a research work outbox row. */
@Entity
@Table(name = "research_work_source_reservations")
public class ResearchWorkSourceReservationEntry {

    @Id
    private UUID requestId;

    @Column(nullable = false, length = 64)
    private String reservationType;

    @Column(nullable = false, length = 256)
    private String sourceId;

    @Column(nullable = false)
    private Instant createdAt;

    protected ResearchWorkSourceReservationEntry() {
    }

    public ResearchWorkSourceReservationEntry(UUID requestId, String reservationType, String sourceId) {
        this.requestId = requestId;
        this.reservationType = reservationType;
        this.sourceId = sourceId;
    }

    public UUID getRequestId() { return requestId; }
    public String getReservationType() { return reservationType; }
    public String getSourceId() { return sourceId; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void create() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
