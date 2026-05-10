package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One directed relationship edge between two papers. A row is written every time
 * a paper's narration references another paper's ID, regardless of whether the
 * target paper exists in the DB at the time of writing — dangling edges remain
 * queryable as the graph grows.
 *
 * Phase 0 introduced the basic citation edge. Phase 1 Iter 3 added typed
 * relationships ({@link RelationshipType}), classifier provenance, and a
 * confidence score so {@link PaperGraphService} can detect contradiction
 * clusters and rank citations by classifier trust.
 *
 * Edges with {@code classifiedByBotId == null} were extracted by the post-narration
 * heuristic (regex / structured-output fallback) and default to
 * {@link RelationshipType#EXTENDS} until an LLM classifier upgrades them.
 */
@Entity
@Table(
        name = "paper_citations",
        indexes = {
                @Index(name = "idx_paper_citations_citing", columnList = "citing_paper_id"),
                @Index(name = "idx_paper_citations_cited",  columnList = "cited_paper_id"),
                @Index(name = "idx_paper_citations_type",   columnList = "relationship_type")
        }
)
public class PaperCitation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "citing_paper_id", nullable = false)
    private String citingPaperId;

    @Column(name = "cited_paper_id", nullable = false)
    private String citedPaperId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 24)
    private RelationshipType relationshipType = RelationshipType.EXTENDS;

    /** Null when the edge was extracted heuristically rather than by an LLM classifier. */
    @Column(name = "classified_by_bot_id")
    private UUID classifiedByBotId;

    /** Classifier-reported confidence in [0,1]. Defaults to 1.0 for heuristic edges. */
    @Column(nullable = false)
    private double confidence = 1.0;

    protected PaperCitation() {}

    public PaperCitation(String citingPaperId, String citedPaperId, UUID roomId, Instant createdAt) {
        this(citingPaperId, citedPaperId, roomId, createdAt, RelationshipType.EXTENDS, null, 1.0);
    }

    public PaperCitation(String citingPaperId, String citedPaperId, UUID roomId, Instant createdAt,
                         RelationshipType relationshipType, UUID classifiedByBotId, double confidence) {
        this.citingPaperId     = citingPaperId;
        this.citedPaperId      = citedPaperId;
        this.roomId            = roomId;
        this.createdAt         = createdAt;
        this.relationshipType  = relationshipType != null ? relationshipType : RelationshipType.EXTENDS;
        this.classifiedByBotId = classifiedByBotId;
        this.confidence        = Math.max(0.0, Math.min(1.0, confidence));
    }

    public UUID             getId()                { return id; }
    public String           getCitingPaperId()     { return citingPaperId; }
    public String           getCitedPaperId()      { return citedPaperId; }
    public UUID             getRoomId()            { return roomId; }
    public Instant          getCreatedAt()         { return createdAt; }
    public RelationshipType getRelationshipType()  { return relationshipType; }
    public UUID             getClassifiedByBotId() { return classifiedByBotId; }
    public double           getConfidence()        { return confidence; }

    public void setRelationshipType(RelationshipType t) {
        this.relationshipType = t != null ? t : RelationshipType.EXTENDS;
    }
    public void setClassifiedByBotId(UUID id) { this.classifiedByBotId = id; }
    public void setConfidence(double c)       { this.confidence = Math.max(0.0, Math.min(1.0, c)); }
}
