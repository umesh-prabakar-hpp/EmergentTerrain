package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One open question on a {@link ResearchProgram}. Phase 1 Iter 4.
 *
 * Open questions are the queue Phase 2's ExperimentPlannerService will pull
 * from. For Iter 4 they're populated from the LLM's follow-up suggestions
 * and the program's initial hypothesis; nothing in Iter 4 itself reads from
 * them (the existing pendingFollowUp path remains in charge).
 */
@Entity
@Table(
        name = "program_open_questions",
        indexes = {
                @Index(name = "idx_open_questions_program", columnList = "program_id"),
                @Index(name = "idx_open_questions_status",  columnList = "status")
        }
)
public class ProgramOpenQuestion {

    public enum Status { OPEN, BEING_INVESTIGATED, RESOLVED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    /** 1.0 = totally open, 0.0 = resolved. Mutated as evidence accumulates. */
    @Column(nullable = false)
    private double uncertainty = 1.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.OPEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProgramOpenQuestion() {}

    public ProgramOpenQuestion(UUID programId, String question, Instant createdAt) {
        this.programId = programId;
        this.question  = question;
        this.createdAt = createdAt;
    }

    public UUID    getId()          { return id; }
    public UUID    getProgramId()   { return programId; }
    public String  getQuestion()    { return question; }
    public double  getUncertainty() { return uncertainty; }
    public Status  getStatus()      { return status; }
    public Instant getCreatedAt()   { return createdAt; }

    public void setUncertainty(double u) { this.uncertainty = Math.max(0.0, Math.min(1.0, u)); }
    public void setStatus(Status s)      { this.status = s; }
}
