package com.emergentterrain.research.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** Immutable, append-only AI assessment of a reviewer-remediation request. */
@Entity
@Table(name = "research_protocol_reuse_remediation_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_research_reuse_remediation_revision",
                columnNames = {"paper_id", "assessment_revision"}),
        indexes = @Index(name = "research_reuse_remediation_paper_revision_idx",
                columnList = "paper_id,assessment_revision"))
public class ResearchProtocolReuseRemediationAssessment {
    @Id private UUID assessmentId;
    @Column(name = "paper_id", nullable = false, length = 128) private String paperId;
    @Column(name = "source_paper_id", nullable = false, length = 128) private String sourcePaperId;
    @Column(name = "assessment_revision", nullable = false) private int assessmentRevision;
    @Column(name = "assessor_model", nullable = false, length = 256) private String assessorModel;
    @Enumerated(EnumType.STRING) @Column(name = "outcome", nullable = false, length = 32)
    private ProtocolReuseRemediationOutcome outcome;
    @Column(name = "rationale", nullable = false, columnDefinition = "text") private String rationale;
    @Column(name = "evidence_references_json", nullable = false, columnDefinition = "text")
    private String evidenceReferencesJson;
    @Column(name = "assessed_at", nullable = false) private Instant assessedAt;

    protected ResearchProtocolReuseRemediationAssessment() { }

    public ResearchProtocolReuseRemediationAssessment(UUID assessmentId, String paperId, String sourcePaperId,
            int assessmentRevision, String assessorModel, ProtocolReuseRemediationOutcome outcome,
            String rationale, String evidenceReferencesJson, Instant assessedAt) {
        this.assessmentId = assessmentId; this.paperId = paperId; this.sourcePaperId = sourcePaperId;
        this.assessmentRevision = assessmentRevision; this.assessorModel = assessorModel;
        this.outcome = outcome; this.rationale = rationale; this.evidenceReferencesJson = evidenceReferencesJson;
        this.assessedAt = assessedAt;
    }
    public int assessmentRevision() { return assessmentRevision; }
    public String assessorModel() { return assessorModel; }
    public ProtocolReuseRemediationOutcome outcome() { return outcome; }
    public Instant assessedAt() { return assessedAt; }
}
