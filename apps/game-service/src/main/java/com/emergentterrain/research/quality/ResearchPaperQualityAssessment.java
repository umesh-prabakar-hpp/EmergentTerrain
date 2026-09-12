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

/**
 * Immutable, versioned AI quality assessment for one persisted research output.
 *
 * <p>Rows are appended rather than updated. This keeps a prior assessment auditable after new
 * replication or contradictory evidence produces a later adjudication.
 */
@Entity
@Table(
        name = "research_paper_quality_assessments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_research_paper_quality_assessment_revision",
                columnNames = {"paper_id", "assessment_revision"}),
        indexes = @Index(
                name = "research_paper_quality_assessment_paper_revision_idx",
                columnList = "paper_id,assessment_revision")
)
public class ResearchPaperQualityAssessment {

    @Id
    private UUID assessmentId;

    @Column(name = "paper_id", nullable = false, length = 128)
    private String paperId;

    @Column(name = "assessment_revision", nullable = false)
    private int assessmentRevision;

    @Column(name = "rubric_version", nullable = false, length = 128)
    private String rubricVersion;

    @Column(name = "assessor_model", nullable = false, length = 256)
    private String assessorModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 64)
    private ResearchQualityGrade grade;

    @Column(name = "rationale", nullable = false, columnDefinition = "text")
    private String rationale;

    @Column(name = "evidence_references_json", nullable = false, columnDefinition = "text")
    private String evidenceReferencesJson;

    @Column(name = "unmet_requirements_json", nullable = false, columnDefinition = "text")
    private String unmetRequirementsJson;

    @Column(name = "contradictions_json", nullable = false, columnDefinition = "text")
    private String contradictionsJson;

    @Column(name = "reviewer_summary", columnDefinition = "text")
    private String reviewerSummary;

    @Column(name = "replication_summary", columnDefinition = "text")
    private String replicationSummary;

    @Column(name = "benchmark_summary", columnDefinition = "text")
    private String benchmarkSummary;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    protected ResearchPaperQualityAssessment() {}

    public ResearchPaperQualityAssessment(
            UUID assessmentId,
            String paperId,
            int assessmentRevision,
            String rubricVersion,
            String assessorModel,
            ResearchQualityGrade grade,
            String rationale,
            String evidenceReferencesJson,
            String unmetRequirementsJson,
            String contradictionsJson,
            String reviewerSummary,
            String replicationSummary,
            String benchmarkSummary,
            Instant assessedAt) {
        this.assessmentId = assessmentId;
        this.paperId = paperId;
        this.assessmentRevision = assessmentRevision;
        this.rubricVersion = rubricVersion;
        this.assessorModel = assessorModel;
        this.grade = grade;
        this.rationale = rationale;
        this.evidenceReferencesJson = evidenceReferencesJson;
        this.unmetRequirementsJson = unmetRequirementsJson;
        this.contradictionsJson = contradictionsJson;
        this.reviewerSummary = reviewerSummary;
        this.replicationSummary = replicationSummary;
        this.benchmarkSummary = benchmarkSummary;
        this.assessedAt = assessedAt;
    }

    public UUID assessmentId() { return assessmentId; }
    public String paperId() { return paperId; }
    public int assessmentRevision() { return assessmentRevision; }
    public String rubricVersion() { return rubricVersion; }
    public String assessorModel() { return assessorModel; }
    public ResearchQualityGrade grade() { return grade; }
    public String rationale() { return rationale; }
    public String evidenceReferencesJson() { return evidenceReferencesJson; }
    public String unmetRequirementsJson() { return unmetRequirementsJson; }
    public String contradictionsJson() { return contradictionsJson; }
    public String reviewerSummary() { return reviewerSummary; }
    public String replicationSummary() { return replicationSummary; }
    public String benchmarkSummary() { return benchmarkSummary; }
    public Instant assessedAt() { return assessedAt; }
}
