package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 7 — one reputation wager on an {@link EpistemicMarket}.
 *
 * reputationStaked is the absolute amount the bot has put up (already
 * bounded by EpistemicMarketService's MAX_STAKE_FRACTION). On resolution,
 * winning stakes gain reputation proportional to their stake; losing
 * stakes lose it.
 */
@Entity
@Table(
        name = "market_stakes",
        indexes = {
                @Index(name = "idx_stakes_market", columnList = "market_id"),
                @Index(name = "idx_stakes_bot",    columnList = "bot_id")
        }
)
public class MarketStake {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "market_id", nullable = false)
    private UUID marketId;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EpistemicMarket.StakeSide side;

    @Column(name = "reputation_staked", nullable = false)
    private double reputationStaked;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    /** Set on market resolution: true when this stake's side won. Null until resolved. */
    @Column(name = "won_on_resolution")
    private Boolean wonOnResolution;

    protected MarketStake() {}

    public MarketStake(UUID marketId, UUID botId, EpistemicMarket.StakeSide side,
                       double reputationStaked, Instant placedAt) {
        this.marketId         = marketId;
        this.botId            = botId;
        this.side             = side;
        this.reputationStaked = Math.max(0.0, reputationStaked);
        this.placedAt         = placedAt;
    }

    public UUID                       getId()               { return id; }
    public UUID                       getMarketId()         { return marketId; }
    public UUID                       getBotId()            { return botId; }
    public EpistemicMarket.StakeSide  getSide()             { return side; }
    public double                     getReputationStaked() { return reputationStaked; }
    public Instant                    getPlacedAt()         { return placedAt; }
    public Boolean                    getWonOnResolution()  { return wonOnResolution; }

    public void setWonOnResolution(Boolean won) { this.wonOnResolution = won; }
}
