package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 4b — one allocation event from {@link FundingAgency} to a
 * {@link University}.
 *
 * Persisted so the allocation loop's behaviour is observable: how often
 * grants fire, how much each transferred, and what room-wide score drove
 * the decision. The score breakdown (trackRecord / calibration / novelty)
 * is stored as separate columns so dashboards can chart the input signals
 * over time.
 *
 * MVP is room-level: one grant per allocation cycle, transferred from the
 * agency to the room's single University. Phase 4c will refine this into
 * per-bot or per-program grants if multiple universities per room or
 * grant competition behaviour is added.
 */
@Entity
@Table(
        name = "grants",
        indexes = {
                @Index(name = "idx_grants_room",       columnList = "room_id"),
                @Index(name = "idx_grants_university", columnList = "university_id"),
                @Index(name = "idx_grants_agency",     columnList = "agency_id")
        }
)
public class Grant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Column(name = "university_id", nullable = false)
    private UUID universityId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false)
    private double amount;

    /** Mean weighted score across the room's bots at allocation time. */
    @Column(name = "score_used", nullable = false)
    private double scoreUsed;

    /** Score-breakdown columns for dashboards. */
    @Column(name = "track_record_component", nullable = false)
    private double trackRecordComponent;

    @Column(name = "calibration_component", nullable = false)
    private double calibrationComponent;

    @Column(name = "novelty_component", nullable = false)
    private double noveltyComponent;

    @Column(name = "game_tick", nullable = false)
    private long gameTick;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Grant() {}

    public Grant(UUID agencyId, UUID universityId, UUID roomId,
                 double amount, double scoreUsed,
                 double trackRecordComponent, double calibrationComponent, double noveltyComponent,
                 long gameTick, Instant createdAt) {
        this.agencyId             = agencyId;
        this.universityId         = universityId;
        this.roomId               = roomId;
        this.amount               = amount;
        this.scoreUsed            = scoreUsed;
        this.trackRecordComponent = trackRecordComponent;
        this.calibrationComponent = calibrationComponent;
        this.noveltyComponent     = noveltyComponent;
        this.gameTick             = gameTick;
        this.createdAt            = createdAt;
    }

    public UUID    getId()                    { return id; }
    public UUID    getAgencyId()              { return agencyId; }
    public UUID    getUniversityId()          { return universityId; }
    public UUID    getRoomId()                { return roomId; }
    public double  getAmount()                { return amount; }
    public double  getScoreUsed()             { return scoreUsed; }
    public double  getTrackRecordComponent()  { return trackRecordComponent; }
    public double  getCalibrationComponent()  { return calibrationComponent; }
    public double  getNoveltyComponent()      { return noveltyComponent; }
    public long    getGameTick()              { return gameTick; }
    public Instant getCreatedAt()             { return createdAt; }
}
