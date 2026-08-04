package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2 Iter 1 — one detected surprise: an observed value far from the
 * bot's running prior, or far from the paper's pre-registered prediction.
 *
 * High-surprise events are the signal Phase 2 Iter 5's experiment planner
 * uses to bias replication priority and to propose new open questions on
 * the bot's research program (Phase 1 Iter 4). For now they're just
 * persisted and queryable.
 */
@Entity
@Table(
        name = "surprise_events",
        indexes = {
                @Index(name = "idx_surprise_paper",  columnList = "paper_id"),
                @Index(name = "idx_surprise_bot",    columnList = "bot_id"),
                @Index(name = "idx_surprise_room",   columnList = "room_id"),
                @Index(name = "idx_surprise_score",  columnList = "surprise_score")
        }
)
public class SurpriseEvent {

    public enum Source {
        /** Surprise relative to the bot's running ExpectationPrior. */
        PRIOR,
        /** Surprise relative to the paper's PreRegistration.prediction. */
        PRE_REGISTRATION
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "paper_id", nullable = false, length = 64)
    private String paperId;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 64)
    private String domain;

    /** Observed value minus expected (sign preserved). */
    @Column(nullable = false)
    private double residual;

    /** Magnitude in standard deviations from the prior mean (always non-negative). */
    @Column(name = "surprise_score", nullable = false)
    private double surpriseScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Source source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SurpriseEvent() {}

    public SurpriseEvent(String paperId, UUID botId, UUID roomId, String domain,
                         double residual, double surpriseScore, Source source,
                         Instant createdAt) {
        this.paperId       = paperId;
        this.botId         = botId;
        this.roomId        = roomId;
        this.domain        = domain;
        this.residual      = residual;
        this.surpriseScore = surpriseScore;
        this.source        = source;
        this.createdAt     = createdAt;
    }

    public UUID    getId()           { return id; }
    public String  getPaperId()      { return paperId; }
    public UUID    getBotId()        { return botId; }
    public UUID    getRoomId()       { return roomId; }
    public String  getDomain()       { return domain; }
    public double  getResidual()     { return residual; }
    public double  getSurpriseScore() { return surpriseScore; }
    public Source  getSource()       { return source; }
    public Instant getCreatedAt()    { return createdAt; }
}
