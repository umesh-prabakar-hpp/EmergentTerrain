package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Research-owned persisted shadow observation; it is deliberately separate from papers and results. */
@Entity
@Table(name = "research_computation_audits")
public class ResearchComputationAuditRecord {
    @Id private UUID auditId;
    @Column(nullable = false, unique = true) private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false, length = 128) private String auditVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, columnDefinition = "text") private String observedOutputJson;
    @Column(nullable = false) private Instant observedAt;

    protected ResearchComputationAuditRecord() { }

    ResearchComputationAuditRecord(ResearchComputationAudit audit, String observedOutputJson) {
        this.auditId = audit.auditId();
        this.draftId = audit.draftId();
        this.requestId = audit.requestId();
        this.auditVersion = audit.auditVersion();
        this.inputFingerprint = audit.inputFingerprint();
        this.observedOutputJson = observedOutputJson;
        this.observedAt = audit.observedAt();
    }

    ResearchComputationAudit toAudit(ObjectMapper mapper) {
        try {
            return new ResearchComputationAudit(auditId, draftId, requestId, auditVersion, inputFingerprint,
                    mapper.readTree(observedOutputJson), observedAt);
        } catch (Exception invalidOutput) {
            throw new IllegalStateException("Stored computation audit has invalid observed output", invalidOutput);
        }
    }
}
