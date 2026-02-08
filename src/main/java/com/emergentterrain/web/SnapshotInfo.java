package com.emergentterrain.web;

import java.time.Instant;

public class SnapshotInfo {
    private String id;
    private String stateHash;
    private Instant createdAt;

    public SnapshotInfo() {
    }

    public SnapshotInfo(String id, String stateHash, Instant createdAt) {
        this.id = id;
        this.stateHash = stateHash;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStateHash() {
        return stateHash;
    }

    public void setStateHash(String stateHash) {
        this.stateHash = stateHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
