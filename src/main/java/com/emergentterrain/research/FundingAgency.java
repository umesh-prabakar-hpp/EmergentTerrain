package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 8 — allocates compute budget to universities.
 *
 * MVP scaffold: one FundingAgency per room, drawing nominally from
 * GovernmentState.federal.budget.education (read at policy-update time).
 * The actual grant-allocation loop is deferred — for now the agency
 * tracks availableFunds and a simple policy that Phase 3b will use.
 *
 * grantPolicy is a low-dimensional weighting vector for now:
 *   trackRecordWeight   — how much past prestige biases grants
 *   calibrationWeight   — how much epistemic-market calibration biases (Phase 3 Iter 7)
 *   noveltyWeight       — how much novelty boosts a grant proposal
 * Defaults sum to 1.0 with equal thirds.
 */
@Entity
@Table(
        name = "funding_agencies",
        indexes = {
                @Index(name = "idx_funding_room", columnList = "room_id")
        }
)
public class FundingAgency {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Federal/state/district node this agency draws its budget from. Null for room-scope. */
    @Column(name = "parent_government_node_id")
    private UUID parentGovernmentNodeId;

    @Column(name = "available_funds", nullable = false)
    private double availableFunds = 0.0;

    @Column(name = "track_record_weight", nullable = false)
    private double trackRecordWeight = 0.34;

    @Column(name = "calibration_weight", nullable = false)
    private double calibrationWeight = 0.33;

    @Column(name = "novelty_weight", nullable = false)
    private double noveltyWeight = 0.33;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FundingAgency() {}

    public FundingAgency(UUID roomId, String name, UUID parentGovernmentNodeId, Instant createdAt) {
        this.roomId                 = roomId;
        this.name                   = name;
        this.parentGovernmentNodeId = parentGovernmentNodeId;
        this.createdAt              = createdAt;
    }

    public UUID    getId()                     { return id; }
    public UUID    getRoomId()                 { return roomId; }
    public String  getName()                   { return name; }
    public UUID    getParentGovernmentNodeId() { return parentGovernmentNodeId; }
    public double  getAvailableFunds()         { return availableFunds; }
    public double  getTrackRecordWeight()      { return trackRecordWeight; }
    public double  getCalibrationWeight()      { return calibrationWeight; }
    public double  getNoveltyWeight()          { return noveltyWeight; }
    public Instant getCreatedAt()              { return createdAt; }

    public void setAvailableFunds(double f)    { this.availableFunds = Math.max(0.0, f); }
    public void setTrackRecordWeight(double w) { this.trackRecordWeight = clampWeight(w); }
    public void setCalibrationWeight(double w) { this.calibrationWeight = clampWeight(w); }
    public void setNoveltyWeight(double w)     { this.noveltyWeight = clampWeight(w); }

    private static double clampWeight(double w) {
        return Math.max(0.0, Math.min(1.0, w));
    }
}
