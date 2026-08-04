package com.emergentterrain.researchservice;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/** Research-owned replica of the immutable bot input received in a game execution snapshot. */
@Entity(name = "research_bot_replicas")
public class ResearchBotReplica {

    @Id
    private UUID botId;
    private UUID roomId;
    private String botType;
    private String lifecycleJson;
    private long gameTick;
    private boolean active;
    private Instant updatedAt;

    protected ResearchBotReplica() {
    }

    public ResearchBotReplica(UUID botId, UUID roomId, String botType, String lifecycleJson,
                              long gameTick, boolean active) {
        this.botId = botId;
        this.roomId = roomId;
        this.botType = botType;
        this.lifecycleJson = lifecycleJson;
        this.gameTick = gameTick;
        this.active = active;
    }

    public UUID getBotId() { return botId; }
    public UUID getRoomId() { return roomId; }
    public String getBotType() { return botType; }
    public String getLifecycleJson() { return lifecycleJson; }
    public long getGameTick() { return gameTick; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }

    void refresh(String botType, String lifecycleJson, long gameTick, boolean active) {
        this.botType = botType;
        this.lifecycleJson = lifecycleJson;
        this.gameTick = gameTick;
        this.active = active;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
