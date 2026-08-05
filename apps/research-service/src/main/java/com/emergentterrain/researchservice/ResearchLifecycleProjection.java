package com.emergentterrain.researchservice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A research-owned predicted lifecycle stream used only for Phase 42 parity shadowing. */
@Entity
@Table(name = "research_lifecycle_projections")
public class ResearchLifecycleProjection {

    @Id
    private UUID botId;
    private UUID roomId;
    @Column(columnDefinition = "text")
    private String lifecycleJson;
    private long lastObservedGameTick;
    private long lastExecutionSequence;
    private String executionEpoch;
    private Instant updatedAt;

    protected ResearchLifecycleProjection() {
    }

    ResearchLifecycleProjection(UUID botId, UUID roomId, String lifecycleJson, long lastObservedGameTick) {
        this(botId, roomId, lifecycleJson, lastObservedGameTick, 0L, null);
    }

    ResearchLifecycleProjection(UUID botId, UUID roomId, String lifecycleJson, long lastObservedGameTick,
                                long lastExecutionSequence, String executionEpoch) {
        this.botId = botId;
        this.roomId = roomId;
        this.lifecycleJson = lifecycleJson;
        this.lastObservedGameTick = lastObservedGameTick;
        this.lastExecutionSequence = lastExecutionSequence;
        this.executionEpoch = executionEpoch;
    }

    UUID getBotId() { return botId; }
    UUID getRoomId() { return roomId; }
    String getLifecycleJson() { return lifecycleJson; }
    long getLastObservedGameTick() { return lastObservedGameTick; }
    long getLastExecutionSequence() { return lastExecutionSequence; }
    String getExecutionEpoch() { return executionEpoch; }

    void observe(String lifecycleJson, long gameTick, long executionSequence, String executionEpoch) {
        this.lifecycleJson = lifecycleJson;
        this.lastObservedGameTick = gameTick;
        this.lastExecutionSequence = executionSequence;
        this.executionEpoch = executionEpoch;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
