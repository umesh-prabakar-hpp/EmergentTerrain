package com.emergentterrain.research;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * One sampled snapshot of a bot's reputation trajectory. Phase 1 Iter 2.
 *
 * Sampled (not per-tick) by {@link BotLifecycleService} every
 * {@code SAMPLE_INTERVAL_TICKS} game ticks per bot. The current Welford-style
 * stats (publishedCount, rejectedCount, reputation) are captured so a future
 * ResearchIdentity view can describe the bot's research arc rather than just
 * its current state.
 *
 * Indexed on bot_id so per-bot trajectory queries are cheap.
 */
@Entity
@Table(
        name = "reputation_history",
        indexes = {
                @Index(name = "idx_reputation_history_bot", columnList = "bot_id"),
                @Index(name = "idx_reputation_history_room", columnList = "room_id")
        }
)
public class ReputationHistory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "game_tick", nullable = false)
    private long gameTick;

    @Column(nullable = false)
    private double reputation;

    @Column(name = "published_count", nullable = false)
    private int publishedCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_phase", length = 16, nullable = false)
    private BotLifecycleState.Phase lifecyclePhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "research_status", length = 16, nullable = false)
    private BotLifecycleState.ResearchStatus researchStatus;

    protected ReputationHistory() {}

    public ReputationHistory(UUID botId, UUID roomId, long gameTick,
                             double reputation, int publishedCount, int rejectedCount,
                             BotLifecycleState.Phase lifecyclePhase,
                             BotLifecycleState.ResearchStatus researchStatus) {
        this.botId          = botId;
        this.roomId         = roomId;
        this.gameTick       = gameTick;
        this.reputation     = reputation;
        this.publishedCount = publishedCount;
        this.rejectedCount  = rejectedCount;
        this.lifecyclePhase = lifecyclePhase;
        this.researchStatus = researchStatus;
    }

    public UUID                            getId()              { return id; }
    public UUID                            getBotId()           { return botId; }
    public UUID                            getRoomId()          { return roomId; }
    public long                            getGameTick()        { return gameTick; }
    public double                          getReputation()      { return reputation; }
    public int                             getPublishedCount()  { return publishedCount; }
    public int                             getRejectedCount()   { return rejectedCount; }
    public BotLifecycleState.Phase         getLifecyclePhase()  { return lifecyclePhase; }
    public BotLifecycleState.ResearchStatus getResearchStatus() { return researchStatus; }
}
