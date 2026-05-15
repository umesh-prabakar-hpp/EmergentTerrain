package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 8 — a research institution that hosts bots within a room.
 *
 * MVP scaffold: one University is auto-created per room on first publish
 * when {@code app.research.institutions.enabled} is on. Bots are not yet
 * attached as members (defer the membership table until Phase 3b); the
 * existing room-scoped bot population is implicitly the university's
 * membership for now.
 *
 * dominantMethodology emerges later from the ResearchIdentity distribution
 * of its members; for the MVP it stays null until a follow-up commit wires
 * the aggregate.
 *
 * prestige accumulates from member paper citation counts over time
 * (deferred); computeBudget is a per-room allocation surface for the
 * FundingAgency to write against (deferred).
 */
@Entity
@Table(
        name = "universities",
        indexes = {
                @Index(name = "idx_universities_room", columnList = "room_id")
        }
)
public class University {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Optional FK into the government hierarchy. Null when the room has no government. */
    @Column(name = "hosting_district_id")
    private UUID hostingDistrictId;

    /** Emerges from member ResearchIdentity distribution; null until Phase 3b wires this. */
    @Column(name = "dominant_methodology", length = 64)
    private String dominantMethodology;

    /** Accumulates from member paper citation counts. Updated by InstitutionService. */
    @Column(nullable = false)
    private double prestige = 0.0;

    /** Allocated by FundingAgency. */
    @Column(name = "compute_budget", nullable = false)
    private double computeBudget = 0.0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected University() {}

    public University(UUID roomId, String name, UUID hostingDistrictId, Instant createdAt) {
        this.roomId            = roomId;
        this.name              = name;
        this.hostingDistrictId = hostingDistrictId;
        this.createdAt         = createdAt;
    }

    public UUID    getId()                  { return id; }
    public UUID    getRoomId()              { return roomId; }
    public String  getName()                { return name; }
    public UUID    getHostingDistrictId()   { return hostingDistrictId; }
    public String  getDominantMethodology() { return dominantMethodology; }
    public double  getPrestige()            { return prestige; }
    public double  getComputeBudget()       { return computeBudget; }
    public Instant getCreatedAt()           { return createdAt; }

    public void setName(String n)                  { this.name = n; }
    public void setDominantMethodology(String m)   { this.dominantMethodology = m; }
    public void setPrestige(double p)              { this.prestige = Math.max(0.0, p); }
    public void setComputeBudget(double b)         { this.computeBudget = Math.max(0.0, b); }
}
