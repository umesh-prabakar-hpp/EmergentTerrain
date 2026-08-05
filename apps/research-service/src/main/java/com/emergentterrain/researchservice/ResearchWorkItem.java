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

    @PrePersist
    void ensureAcceptedAt() {
        if (acceptedAt == null) {
            acceptedAt = Instant.now();
        }
    }
}
