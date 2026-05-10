package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 8 — a per-domain journal that papers route through.
 *
 * MVP scaffold: one Journal is auto-created per (room, domain) on first
 * publish in that domain. Acceptance/rejection still happens at the
 * existing peer-review gate; the journal's own rejectionRate and
 * impactFactor are placeholder columns until Phase 3b wires the
 * journal-mediated review path.
 *
 * focusDomains is the single domain string the journal covers, kept as a
 * column (not @ElementCollection) for the MVP — multi-domain journals can
 * be modelled by promoting it to a join table later.
 */
@Entity
@Table(
        name = "journals",
        indexes = {
                @Index(name = "idx_journals_room",          columnList = "room_id"),
                @Index(name = "idx_journals_room_domain",   columnList = "room_id,focus_domain")
        }
)
public class Journal {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Single-domain MVP; multi-domain journals would promote this to a join table. */
    @Column(name = "focus_domain", nullable = false, length = 64)
    private String focusDomain;

    /** Updated as papers accumulate; emerges from review behaviour. */
    @Column(name = "rejection_rate", nullable = false)
    private double rejectionRate = 0.0;

    /** Average citation count of accepted papers; updated by InstitutionService. */
    @Column(name = "impact_factor", nullable = false)
    private double impactFactor = 0.0;

    /** Running paper count for impact-factor / rejection-rate denominators. */
    @Column(name = "papers_accepted", nullable = false)
    private int papersAccepted = 0;

    @Column(name = "papers_rejected", nullable = false)
    private int papersRejected = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Journal() {}

    public Journal(UUID roomId, String name, String focusDomain, Instant createdAt) {
        this.roomId      = roomId;
        this.name        = name;
        this.focusDomain = focusDomain;
        this.createdAt   = createdAt;
    }

    public UUID    getId()             { return id; }
    public UUID    getRoomId()         { return roomId; }
    public String  getName()           { return name; }
    public String  getFocusDomain()    { return focusDomain; }
    public double  getRejectionRate()  { return rejectionRate; }
    public double  getImpactFactor()   { return impactFactor; }
    public int     getPapersAccepted() { return papersAccepted; }
    public int     getPapersRejected() { return papersRejected; }
    public Instant getCreatedAt()      { return createdAt; }

    public void setName(String n)            { this.name = n; }
    public void setRejectionRate(double r)   { this.rejectionRate = Math.max(0.0, Math.min(1.0, r)); }
    public void setImpactFactor(double f)    { this.impactFactor = Math.max(0.0, f); }
    public void setPapersAccepted(int n)     { this.papersAccepted = Math.max(0, n); }
    public void setPapersRejected(int n)     { this.papersRejected = Math.max(0, n); }

    public void recordAcceptance() {
        papersAccepted++;
        recomputeRejectionRate();
    }

    public void recordRejection() {
        papersRejected++;
        recomputeRejectionRate();
    }

    private void recomputeRejectionRate() {
        int total = papersAccepted + papersRejected;
        rejectionRate = total > 0 ? (double) papersRejected / total : 0.0;
    }
}
