package com.emergentterrain.researchservice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Research-owned frozen planning record; downstream stages consume this instead of mutable source state. */
@Entity
@Table(name = "research_draft_plans")
public class ResearchDraftPlanRecord {
    @Id private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID subjectId;
    @Column(nullable = false, length = 128) private String domain;
    @Column(nullable = false, length = 512) private String topic;
    @Column(nullable = false, length = 32) private String eligibility;
    @Column(nullable = false, length = 128) private String selectorVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false) private Instant plannedAt;
    protected ResearchDraftPlanRecord() { }
    public ResearchDraftPlanRecord(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId, String domain,
                                   String topic, String eligibility, String selectorVersion, String inputFingerprint,
                                   Instant plannedAt) {
        this.draftId=draftId; this.requestId=requestId; this.workspaceId=workspaceId; this.subjectId=subjectId;
        this.domain=domain; this.topic=topic; this.eligibility=eligibility;
        this.selectorVersion=selectorVersion; this.inputFingerprint=inputFingerprint; this.plannedAt=plannedAt;
    }
}
