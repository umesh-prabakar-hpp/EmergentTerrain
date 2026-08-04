package com.emergentterrain.researchservice;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/** Durable ownership record for a research-only room worker. */
@Entity(name = "research_service_checkpoints")
public class ResearchWorkerCheckpoint {

    @Id
    private UUID roomId;
    private long lastTick;
    private Instant updatedAt;

    protected ResearchWorkerCheckpoint() {
    }

    public ResearchWorkerCheckpoint(UUID roomId, long lastTick) {
        this.roomId = roomId;
        this.lastTick = lastTick;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public long getLastTick() {
        return lastTick;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
