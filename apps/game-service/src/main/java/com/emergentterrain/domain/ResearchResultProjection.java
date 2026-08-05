package com.emergentterrain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Game-owned display projection of an immutable research result; it has no authority over research. */
@Entity
@Table(name = "research_result_projections")
public class ResearchResultProjection {
    @Id private UUID resultId;
    @Column(nullable = false) private UUID requestId;
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false, length = 64) private String outcome;
    @Column(nullable = false, columnDefinition = "text") private String summary;
    @Column(nullable = false, columnDefinition = "text") private String findingsJson;
    @Column(nullable = false) private Instant receivedAt;
    protected ResearchResultProjection() { }
    public ResearchResultProjection(UUID resultId, UUID requestId, UUID workspaceId, String outcome, String summary,
                                    String findingsJson) {
        this.resultId = resultId; this.requestId = requestId; this.workspaceId = workspaceId;
        this.outcome = outcome; this.summary = summary; this.findingsJson = findingsJson; this.receivedAt = Instant.now();
    }
    public UUID getResultId() { return resultId; }
    public String getOutcome() { return outcome; }
    public String getSummary() { return summary; }
}
