package com.emergentterrain.researchservice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/** Mutable research-owned lifecycle state, seeded once from a game bot snapshot. */
@Entity(name = "research_bot_states")
public class ResearchBotState {

    @Id
    private UUID botId;
    private UUID roomId;
    private String botType;
    @Column(columnDefinition = "text")
    private String lifecycleJson;
    private long lastSourceGameTick;
    private boolean active;
    private Instant updatedAt;

    protected ResearchBotState() {
    }

    public ResearchBotState(UUID botId, UUID roomId, String botType, String lifecycleJson,
                            long lastSourceGameTick, boolean active) {
        this.botId = botId;
        this.roomId = roomId;
        this.botType = botType;
        this.lifecycleJson = lifecycleJson;
        this.lastSourceGameTick = lastSourceGameTick;
        this.active = active;
    }

    public UUID getBotId() { return botId; }
    public UUID getRoomId() { return roomId; }
    public String getBotType() { return botType; }
    public String getLifecycleJson() { return lifecycleJson; }
    public long getLastSourceGameTick() { return lastSourceGameTick; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }

    void refreshSource(String botType, long gameTick, boolean active) {
        this.botType = botType;
        this.lastSourceGameTick = gameTick;
        this.active = active;
    }

    /** Reserved for the extracted university engine; game snapshots never overwrite this value. */
    public void updateResearchLifecycle(String lifecycleJson) {
        this.lifecycleJson = lifecycleJson;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
