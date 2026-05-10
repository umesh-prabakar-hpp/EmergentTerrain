package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One failed approach within a {@link ResearchProgram}. Phase 1 Iter 4.
 *
 * Written when a paper attached to the program comes back DEGENERATE,
 * NULL_RESULT, or peer-review-rejected — so the program carries the
 * "things we already tried that didn't work" history.
 */
@Entity
@Table(
        name = "program_failed_approaches",
        indexes = {
                @Index(name = "idx_failed_approaches_program", columnList = "program_id"),
                @Index(name = "idx_failed_approaches_paper",   columnList = "associated_paper_id")
        }
)
public class ProgramFailedApproach {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false, columnDefinition = "text")
    private String approach;

    @Column(name = "why_failed", columnDefinition = "text")
    private String whyFailed;

    /** Optional: paperId of the paper that recorded this failure. */
    @Column(name = "associated_paper_id")
    private String associatedPaperId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProgramFailedApproach() {}

    public ProgramFailedApproach(UUID programId, String approach, String whyFailed,
                                 String associatedPaperId, Instant createdAt) {
        this.programId         = programId;
        this.approach          = approach;
        this.whyFailed         = whyFailed;
        this.associatedPaperId = associatedPaperId;
        this.createdAt         = createdAt;
    }

    public UUID    getId()                { return id; }
    public UUID    getProgramId()         { return programId; }
    public String  getApproach()          { return approach; }
    public String  getWhyFailed()         { return whyFailed; }
    public String  getAssociatedPaperId() { return associatedPaperId; }
    public Instant getCreatedAt()         { return createdAt; }
}
