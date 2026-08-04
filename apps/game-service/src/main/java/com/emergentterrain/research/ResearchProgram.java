package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 Iter 4 — a longitudinal multi-paper research program owned by a bot.
 *
 * Replaces the old "single follow-up per bot" semantics with durable, queryable
 * program state. A bot's first paper in a domain creates a program; subsequent
 * papers in the same domain attach to it (foreign key on ResearchPaper, nullable
 * during the rollout window). When evidence_for or evidence_against crosses a
 * threshold the program closes with a summary paper.
 *
 * Gated by {@code app.research.programs.enabled}; when the flag is off, the
 * existing pendingFollowUp / researchHypothesis flow stays in charge and no
 * program rows are written.
 */
@Entity
@Table(
        name = "research_programs",
        indexes = {
                @Index(name = "idx_research_programs_owner",  columnList = "owner_bot_id"),
                @Index(name = "idx_research_programs_room",   columnList = "room_id"),
                @Index(name = "idx_research_programs_status", columnList = "status")
        }
)
public class ResearchProgram {

    public enum Status { ACTIVE, CONVERGED, ABANDONED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_bot_id", nullable = false)
    private UUID ownerBotId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 64)
    private String domain;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "long_term_objective", columnDefinition = "text")
    private String longTermObjective;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "paper_count", nullable = false)
    private int paperCount = 0;

    @Column(name = "evidence_for", nullable = false)
    private double evidenceFor = 0.0;

    @Column(name = "evidence_against", nullable = false)
    private double evidenceAgainst = 0.0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_paper_at")
    private Instant lastPaperAt;

    protected ResearchProgram() {}

    public ResearchProgram(UUID ownerBotId, UUID roomId, String domain,
                           String title, String longTermObjective, Instant createdAt) {
        this.ownerBotId        = ownerBotId;
        this.roomId            = roomId;
        this.domain            = domain;
        this.title             = title;
        this.longTermObjective = longTermObjective;
        this.createdAt         = createdAt;
    }

    public UUID    getId()                { return id; }
    public UUID    getOwnerBotId()        { return ownerBotId; }
    public UUID    getRoomId()            { return roomId; }
    public String  getDomain()            { return domain; }
    public String  getTitle()             { return title; }
    public String  getLongTermObjective() { return longTermObjective; }
    public Status  getStatus()            { return status; }
    public int     getPaperCount()        { return paperCount; }
    public double  getEvidenceFor()       { return evidenceFor; }
    public double  getEvidenceAgainst()   { return evidenceAgainst; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getLastPaperAt()       { return lastPaperAt; }

    public void setTitle(String t)              { this.title = t; }
    public void setLongTermObjective(String o)  { this.longTermObjective = o; }
    public void setStatus(Status s)             { this.status = s; }
    public void setPaperCount(int c)            { this.paperCount = c; }
    public void setEvidenceFor(double v)        { this.evidenceFor = v; }
    public void setEvidenceAgainst(double v)    { this.evidenceAgainst = v; }
    public void setLastPaperAt(Instant t)       { this.lastPaperAt = t; }
}
