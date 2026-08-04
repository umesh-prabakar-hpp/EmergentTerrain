package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4 Iter 9 — a single structured finding extracted from a paper's
 * narration. Replaces "prose buried in conclusion" with a queryable
 * record that {@link KnowledgeBaseService} can search by variable, group
 * by domain, and check for contradictions across papers.
 *
 * One paper can produce zero or more {@code FormalFinding} rows. Findings
 * with {@link RelationType#NULL} mean "the paper found no reliable
 * relationship" — they are still recorded because absence of evidence is
 * itself useful for the knowledge base.
 *
 * claimStatus reuses {@link MathPhysicsEngine.ClaimStatus} per the phase
 * doc: that enum already captures SUPPORTED / PARTIAL / REFUTED /
 * OBSERVATIONAL at the computation layer; introducing a third overlapping
 * enum here would be confusing.
 *
 * validRegion captures the parameter range over which the finding is
 * claimed to hold; queries can prune findings whose region doesn't overlap
 * the caller's interval.
 */
@Entity
@Table(
        name = "formal_findings",
        indexes = {
                @Index(name = "idx_findings_paper",     columnList = "paper_id"),
                @Index(name = "idx_findings_domain",    columnList = "domain"),
                @Index(name = "idx_findings_dep_var",   columnList = "dependent_variable"),
                @Index(name = "idx_findings_relation",  columnList = "relation_type")
        }
)
public class FormalFinding {

    @Id
    @GeneratedValue
    private UUID id;

    /** Phase 0 left ResearchPaper keyed by String paperId — match that here. */
    @Column(name = "paper_id", nullable = false, length = 64)
    private String paperId;

    /** Null when extracted by the post-process LLM rather than authored by a bot. */
    @Column(name = "extracted_by_bot_id")
    private UUID extractedByBotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 16)
    private RelationType relationType;

    @Column(name = "dependent_variable", nullable = false, length = 200)
    private String dependentVariable;

    /**
     * Independent variables as a list (an @ElementCollection — Hibernate creates
     * a side table). Ordered so the position-sensitive equation_form column can
     * reference them by index.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "formal_finding_independents",
            joinColumns = @JoinColumn(name = "finding_id")
    )
    @OrderColumn(name = "var_position")
    @Column(name = "independent_variable", length = 200)
    private List<String> independentVariables = new ArrayList<>();

    /** LaTeX or symbolic form (e.g. {@code "trust ~ log(highway_density)"}). Optional. */
    @Column(name = "equation_form", columnDefinition = "text")
    private String equationForm;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_status", nullable = false, length = 24)
    private MathPhysicsEngine.ClaimStatus claimStatus;

    @Column(nullable = false)
    private double confidence;

    /** Lower bound of the parameter range over which the finding is claimed. */
    @Column(name = "valid_region_min", nullable = false)
    private double validRegionMin;

    @Column(name = "valid_region_max", nullable = false)
    private double validRegionMax;

    @Column(nullable = false, length = 64)
    private String domain;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    protected FormalFinding() {}

    public FormalFinding(String paperId, UUID extractedByBotId,
                         RelationType relationType,
                         String dependentVariable, List<String> independentVariables,
                         String equationForm,
                         MathPhysicsEngine.ClaimStatus claimStatus, double confidence,
                         double validRegionMin, double validRegionMax,
                         String domain, Instant extractedAt) {
        this.paperId              = paperId;
        this.extractedByBotId     = extractedByBotId;
        this.relationType         = relationType;
        this.dependentVariable    = dependentVariable;
        this.independentVariables = independentVariables != null
                ? new ArrayList<>(independentVariables) : new ArrayList<>();
        this.equationForm         = equationForm;
        this.claimStatus          = claimStatus;
        this.confidence           = Math.max(0.0, Math.min(1.0, confidence));
        this.validRegionMin       = validRegionMin;
        this.validRegionMax       = validRegionMax;
        this.domain               = domain;
        this.extractedAt          = extractedAt;
    }

    public UUID                          getId()                   { return id; }
    public String                        getPaperId()              { return paperId; }
    public UUID                          getExtractedByBotId()     { return extractedByBotId; }
    public RelationType                  getRelationType()         { return relationType; }
    public String                        getDependentVariable()    { return dependentVariable; }
    public List<String>                  getIndependentVariables() { return independentVariables; }
    public String                        getEquationForm()         { return equationForm; }
    public MathPhysicsEngine.ClaimStatus getClaimStatus()          { return claimStatus; }
    public double                        getConfidence()           { return confidence; }
    public double                        getValidRegionMin()       { return validRegionMin; }
    public double                        getValidRegionMax()       { return validRegionMax; }
    public String                        getDomain()               { return domain; }
    public Instant                       getExtractedAt()          { return extractedAt; }
}
