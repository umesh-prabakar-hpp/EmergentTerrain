package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 8 — coordinates replication campaigns triggered by high-surprise
 * events or contradiction clusters.
 *
 * MVP scaffold: one ReplicationCouncil per room. Member bots are not yet
 * tracked explicitly; the council's "trigger campaign" method is the
 * next layer (Phase 3b) that will pick a panel from the bot population
 * and enqueue replications through {@link ReplicationService}.
 *
 * activeReplicationCount counts in-flight replications the council has
 * commissioned (separate from the room's regular replication queue).
 */
@Entity
@Table(
        name = "replication_councils",
        indexes = {
                @Index(name = "idx_replication_council_room", columnList = "room_id")
        }
)
public class ReplicationCouncil {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "active_replication_count", nullable = false)
    private int activeReplicationCount = 0;

    @Column(name = "completed_count", nullable = false)
    private int completedCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReplicationCouncil() {}

    public ReplicationCouncil(UUID roomId, String name, Instant createdAt) {
        this.roomId    = roomId;
        this.name      = name;
        this.createdAt = createdAt;
    }

    public UUID    getId()                     { return id; }
    public UUID    getRoomId()                 { return roomId; }
    public String  getName()                   { return name; }
    public int     getActiveReplicationCount() { return activeReplicationCount; }
    public int     getCompletedCount()         { return completedCount; }
    public Instant getCreatedAt()              { return createdAt; }

    public void setActiveReplicationCount(int n) { this.activeReplicationCount = Math.max(0, n); }
    public void setCompletedCount(int n)         { this.completedCount = Math.max(0, n); }
}
