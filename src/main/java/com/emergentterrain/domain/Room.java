package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(optional = false)
    private Profile hostProfile;

    private UUID turnOwnerParticipantId;

    private Instant turnExpiresAt;

    private Instant lockUntil;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Phase 3 Iter 6 (sandbox flag): when true, this room is a research
     * sandbox isolated from live player rooms. Bots in sandbox rooms may
     * file intervention bills via {@code WorldInterventionService} without
     * the experiments disrupting human-visible state elsewhere — the
     * sandbox sits alongside live rooms but is not surfaced in
     * default room listings to players.
     *
     * Default false so existing rooms keep their current visibility. New
     * rooms opt in at creation; once set, the flag is not reset.
     */
    @Column(name = "sandbox", nullable = false)
    @ColumnDefault("false")
    private boolean sandbox = false;

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Profile getHostProfile() {
        return hostProfile;
    }

    public void setHostProfile(Profile hostProfile) {
        this.hostProfile = hostProfile;
    }

    public UUID getTurnOwnerParticipantId() {
        return turnOwnerParticipantId;
    }

    public void setTurnOwnerParticipantId(UUID turnOwnerParticipantId) {
        this.turnOwnerParticipantId = turnOwnerParticipantId;
    }

    public Instant getTurnExpiresAt() {
        return turnExpiresAt;
    }

    public void setTurnExpiresAt(Instant turnExpiresAt) {
        this.turnExpiresAt = turnExpiresAt;
    }

    public Instant getLockUntil() {
        return lockUntil;
    }

    public void setLockUntil(Instant lockUntil) {
        this.lockUntil = lockUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }
}
