package com.emergentterrain.researchservice;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/** Append-only parity observation made before the legacy university cycle runs. */
@Entity(name = "research_lifecycle_shadow_records")
public class ResearchLifecycleShadowRecord {

    @Id
    @GeneratedValue
    private UUID id;
    private UUID roomId;
    private long gameTick;
    private int comparedBots;
    private int matchingBots;
    private int mismatchingBots;
    private Instant observedAt = Instant.now();

    protected ResearchLifecycleShadowRecord() {
    }

    public ResearchLifecycleShadowRecord(UUID roomId, long gameTick, int comparedBots,
                                         int matchingBots, int mismatchingBots) {
        this.roomId = roomId;
        this.gameTick = gameTick;
        this.comparedBots = comparedBots;
        this.matchingBots = matchingBots;
        this.mismatchingBots = mismatchingBots;
    }

    public UUID getRoomId() { return roomId; }
    public long getGameTick() { return gameTick; }
    public int getComparedBots() { return comparedBots; }
    public int getMatchingBots() { return matchingBots; }
    public int getMismatchingBots() { return mismatchingBots; }
    public Instant getObservedAt() { return observedAt; }
}
