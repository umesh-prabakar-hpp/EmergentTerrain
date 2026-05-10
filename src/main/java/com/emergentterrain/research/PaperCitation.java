package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One directed citation edge between two papers. A row is written every time a paper's
 * narration references another paper's ID, regardless of whether the target paper exists
 * in the DB at the time of writing — Phase 1 Iteration 3 will resolve dangling edges.
 *
 * Phase 0 stores the edge untyped. Phase 1 Iteration 3 will add relationship_type
 * (SUPPORTS / CONTRADICTS / EXTENDS / …), classified_by_bot_id, and confidence columns
 * via further migrations.
 */
@Entity
@Table(
        name = "paper_citations",
        indexes = {
                @Index(name = "idx_paper_citations_citing", columnList = "citing_paper_id"),
                @Index(name = "idx_paper_citations_cited",  columnList = "cited_paper_id")
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

    protected PaperCitation() {}

    public PaperCitation(String citingPaperId, String citedPaperId, UUID roomId, Instant createdAt) {
        this.citingPaperId = citingPaperId;
        this.citedPaperId  = citedPaperId;
        this.roomId        = roomId;
        this.createdAt     = createdAt;
    }

    public UUID    getId()            { return id; }
    public String  getCitingPaperId() { return citingPaperId; }
    public String  getCitedPaperId()  { return citedPaperId; }
    public UUID    getRoomId()        { return roomId; }
    public Instant getCreatedAt()     { return createdAt; }
}
