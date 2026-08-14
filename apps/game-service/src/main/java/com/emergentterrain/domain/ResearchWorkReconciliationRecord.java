package com.emergentterrain.domain;

import com.emergentterrain.researchcontract.ResearchWorkStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Game-owned, append-only observation of generic research work status during cutover audits. */
@Entity
@Table(name = "research_work_reconciliation_records")
public class ResearchWorkReconciliationRecord {

    @Id
    private UUID id;
    private UUID requestId;
    private String state;
    private int attempts;
    private Instant acceptedAt;
    private Instant completedAt;
    private Instant resultPublishedAt;
    private Instant resultDeliveryClaimedAt;
    private Instant resultDeliveryRejectedAt;
    private String resultDeliveryRejection;
    private Instant observedAt;

    protected ResearchWorkReconciliationRecord() {
    }

    private ResearchWorkReconciliationRecord(ResearchWorkStatus status) {
        this.id = UUID.nameUUIDFromBytes(("research-work-reconciliation:" + status.requestId() + ":"
                + status.state() + ":" + status.attempts() + ":" + status.resultPublishedAt() + ":"
                + status.resultDeliveryClaimedAt() + ":" + status.resultDeliveryRejectedAt() + ":"
                + status.resultDeliveryRejection()).getBytes(StandardCharsets.UTF_8));
        this.requestId = status.requestId();
        this.state = status.state();
        this.attempts = status.attempts();
        this.acceptedAt = status.acceptedAt();
        this.completedAt = status.completedAt();
        this.resultPublishedAt = status.resultPublishedAt();
        this.resultDeliveryClaimedAt = status.resultDeliveryClaimedAt();
        this.resultDeliveryRejectedAt = status.resultDeliveryRejectedAt();
        this.resultDeliveryRejection = status.resultDeliveryRejection();
    }

    public static ResearchWorkReconciliationRecord from(ResearchWorkStatus status) {
        return new ResearchWorkReconciliationRecord(status);
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public String getState() { return state; }
    public Instant getResultDeliveryClaimedAt() { return resultDeliveryClaimedAt; }

    @PrePersist
    void observed() {
        observedAt = Instant.now();
    }
}
