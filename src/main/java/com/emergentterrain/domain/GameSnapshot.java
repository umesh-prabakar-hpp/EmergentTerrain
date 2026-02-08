package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_snapshots")
public class GameSnapshot {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Profile profile;

    @Column(nullable = false)
    private String stateHash;

    @Lob
    @Column(nullable = false)
    private String tilesJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public String getStateHash() {
        return stateHash;
    }

    public void setStateHash(String stateHash) {
        this.stateHash = stateHash;
    }

    public String getTilesJson() {
        return tilesJson;
    }

    public void setTilesJson(String tilesJson) {
        this.tilesJson = tilesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
