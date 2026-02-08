package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

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
}
