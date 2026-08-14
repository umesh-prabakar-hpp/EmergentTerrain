package com.emergentterrain.researchservice;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Research-owned high-water state for the read-only legacy-consumption audit stream. */
@Entity
@Table(name = "research_conjecture_consumption_audit_cursors")
public class ResearchConjectureConsumptionAuditCursor {

    @Id
    private UUID roomId;
    private String epoch;
    private long latestSequence;
    private String lastOutcome;
    private Instant updatedAt;

    protected ResearchConjectureConsumptionAuditCursor() {
    }

    ResearchConjectureConsumptionAuditCursor(UUID roomId, String epoch, long latestSequence, String lastOutcome) {
        this.roomId = roomId;
        this.epoch = epoch;
        this.latestSequence = latestSequence;
        this.lastOutcome = lastOutcome;
    }

    String getEpoch() { return epoch; }
    long getLatestSequence() { return latestSequence; }
    String getLastOutcome() { return lastOutcome; }

    void observe(String epoch, long latestSequence, String outcome) {
        this.epoch = epoch;
        this.latestSequence = latestSequence;
        this.lastOutcome = outcome;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
