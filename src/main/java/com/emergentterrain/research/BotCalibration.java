package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 7 — running calibration stats per bot, accumulated from
 * resolved {@link EpistemicMarket} stakes.
 *
 * calibrationScore = wins / (wins + losses), defaulting to 0.5 (neutral)
 * when no resolutions have happened yet. Used by FundingAgency.GrantPolicy
 * to weight grant allocation: well-calibrated bots get more compute budget.
 *
 * One row per bot; created lazily on the bot's first stake resolution.
 */
@Entity
@Table(
        name = "bot_calibrations",
        indexes = @Index(name = "idx_bot_calibration_bot", columnList = "bot_id", unique = true)
)
public class BotCalibration {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "bot_id", nullable = false, unique = true)
    private UUID botId;

    @Column(nullable = false)
    private int wins = 0;

    @Column(nullable = false)
    private int losses = 0;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    protected BotCalibration() {}

    public BotCalibration(UUID botId, Instant lastUpdatedAt) {
        this.botId         = botId;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /** Win rate in [0,1]; 0.5 (neutral) when no resolutions yet. */
    public double calibrationScore() {
        int total = wins + losses;
        return total > 0 ? (double) wins / total : 0.5;
    }

    public UUID    getId()             { return id; }
    public UUID    getBotId()          { return botId; }
    public int     getWins()           { return wins; }
    public int     getLosses()         { return losses; }
    public Instant getLastUpdatedAt()  { return lastUpdatedAt; }

    public void recordWin(Instant at)  { wins++;   lastUpdatedAt = at; }
    public void recordLoss(Instant at) { losses++; lastUpdatedAt = at; }
}
