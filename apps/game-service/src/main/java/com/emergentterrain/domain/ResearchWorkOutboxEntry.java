package com.emergentterrain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Game-owned durable delivery record for a generic research work request. */
@Entity
@Table(name = "research_work_outbox")
public class ResearchWorkOutboxEntry {

    @Id
    private UUID requestId;

    @Column(nullable = false, length = 64)
    private String workType;

    @Column(nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false)
    private int deliveryAttempts;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant deliveredAt;

    protected ResearchWorkOutboxEntry() {
    }

    public ResearchWorkOutboxEntry(UUID requestId, String workType, String payloadJson) {
        this.requestId = requestId;
        this.workType = workType;
        this.payloadJson = payloadJson;
        this.state = "PENDING";
    }

    public UUID getRequestId() { return requestId; }
    public String getWorkType() { return workType; }
    public String getPayloadJson() { return payloadJson; }
    public String getState() { return state; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void markDelivered() {
        state = "DELIVERED";
        deliveredAt = Instant.now();
    }

    public void recordDeliveryFailure() {
        deliveryAttempts++;
    }

    @PrePersist
    void create() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void update() {
        // Explicit lifecycle methods above are the only state transitions.
    }
}
