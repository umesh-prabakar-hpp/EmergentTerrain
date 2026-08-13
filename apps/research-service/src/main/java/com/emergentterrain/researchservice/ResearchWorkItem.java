package com.emergentterrain.researchservice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Research-owned durable intake record for a generic research request. */
@Entity
@Table(name = "research_work_items")
public class ResearchWorkItem {

    @Id
    private UUID requestId;

    @Column(nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 64)
    private String workType;

    @Column(nullable = false)
    private long sourceRevision;

    @Column(nullable = false, columnDefinition = "text")
    private String requestJson;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false)
    private Instant acceptedAt;

    private int attempts;
    private Instant claimedAt;
    private Instant completedAt;
    private Instant resultPublishedAt;
    private Instant resultDeliveryRejectedAt;

    @Column(length = 64)
    private String resultDeliveryRejection;

    @Column(columnDefinition = "text")
    private String resultJson;

    @Column(columnDefinition = "text")
    private String failureDetail;

    protected ResearchWorkItem() {
    }

    public ResearchWorkItem(UUID requestId, UUID workspaceId, String workType, long sourceRevision,
                            String requestJson, Instant acceptedAt) {
        this.requestId = requestId;
        this.workspaceId = workspaceId;
        this.workType = workType;
        this.sourceRevision = sourceRevision;
        this.requestJson = requestJson;
        this.state = "QUEUED";
        this.acceptedAt = acceptedAt;
    }

    public UUID getRequestId() { return requestId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getWorkType() { return workType; }
    public long getSourceRevision() { return sourceRevision; }
    public String getRequestJson() { return requestJson; }
    public String getState() { return state; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public int getAttempts() { return attempts; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getResultPublishedAt() { return resultPublishedAt; }
    public Instant getResultDeliveryRejectedAt() { return resultDeliveryRejectedAt; }
    public String getResultDeliveryRejection() { return resultDeliveryRejection; }
    public String getResultJson() { return resultJson; }

    public void markRunning(Instant claimedAt) {
        state = "RUNNING";
        attempts++;
        this.claimedAt = claimedAt;
    }

    public void markCompleted(String resultJson) {
        state = "COMPLETED";
        this.resultJson = resultJson;
        completedAt = Instant.now();
        failureDetail = null;
    }

    public void markDeferred(String detail) {
        state = "QUEUED";
        failureDetail = detail;
    }

    public void markFailed(String detail) {
        state = "FAILED";
        failureDetail = detail;
    }

    public void markResultPublished() {
        resultPublishedAt = Instant.now();
        resultDeliveryRejectedAt = null;
        resultDeliveryRejection = null;
    }

    /** Records a game-owned policy rejection so a malformed result cannot retry indefinitely. */
    public void markResultDeliveryRejected(String rejection) {
        resultDeliveryRejectedAt = Instant.now();
        resultDeliveryRejection = rejection == null || rejection.isBlank() ? "HTTP_4XX" : rejection;
    }

    @PrePersist
    void ensureAcceptedAt() {
        if (acceptedAt == null) {
            acceptedAt = Instant.now();
        }
    }
}
