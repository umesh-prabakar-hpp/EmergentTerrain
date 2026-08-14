package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchConjectureConsumptionEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable research-owned evidence or incident from the post-consumption audit stream. */
@Entity
@Table(name = "research_conjecture_consumption_audit_records")
public class ResearchConjectureConsumptionAuditRecord {

    @Id
    private UUID id;
    private UUID roomId;
    private String epoch;
    private long firstSequence;
    private long latestSequence;
    private String outcome;
    private UUID sourceEventId;
    private Long gameTick;
    private String authorBotId;
    private String domain;
    private String sourceId;
    private String selectionKind;
    private Instant sourceObservedAt;
    private Instant recordedAt;

    protected ResearchConjectureConsumptionAuditRecord() {
    }

    static ResearchConjectureConsumptionAuditRecord observed(String epoch,
                                                              ResearchConjectureConsumptionEvent event) {
        ResearchConjectureConsumptionAuditRecord record = new ResearchConjectureConsumptionAuditRecord();
        record.id = event.eventId();
        record.roomId = event.roomId();
        record.epoch = epoch;
        record.firstSequence = event.sequence();
        record.latestSequence = event.sequence();
        record.outcome = ResearchConjectureConsumptionAuditService.Outcome.OBSERVED.name();
        record.sourceEventId = event.eventId();
        record.gameTick = event.gameTick();
        record.authorBotId = event.authorBotId();
        record.domain = event.domain();
        record.sourceId = event.sourceId();
        record.selectionKind = event.selectionKind();
        record.sourceObservedAt = event.observedAt();
        return record;
    }

    static ResearchConjectureConsumptionAuditRecord incident(UUID id, UUID roomId, String epoch,
                                                              long firstSequence, long latestSequence,
                                                              ResearchConjectureConsumptionAuditService.Outcome outcome) {
        ResearchConjectureConsumptionAuditRecord record = new ResearchConjectureConsumptionAuditRecord();
        record.id = id;
        record.roomId = roomId;
        record.epoch = epoch;
        record.firstSequence = firstSequence;
        record.latestSequence = latestSequence;
        record.outcome = outcome.name();
        return record;
    }

    UUID getId() { return id; }
    String getOutcome() { return outcome; }
    long getLatestSequence() { return latestSequence; }

    @PrePersist
    void touch() {
        recordedAt = Instant.now();
    }
}
