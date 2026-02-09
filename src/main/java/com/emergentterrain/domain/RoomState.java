package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_state")
public class RoomState {
    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(optional = false)
    private Room room;

    @Lob
    @Column(nullable = false)
    private String tilesJson;

    @Lob
    private String sharedTokensJson;

    @Lob
    private String sharedQuestsJson;

    @Lob
    private String memoryMarkersJson;

    @Lob
    private String highwaysJson;

    private String stateHash;

    private String previousStateHash;

    private Instant lastBotProposalAt;
    private Instant lastBotTickAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public String getTilesJson() {
        return tilesJson;
    }

    public void setTilesJson(String tilesJson) {
        this.tilesJson = tilesJson;
    }

    public String getSharedTokensJson() {
        return sharedTokensJson;
    }

    public void setSharedTokensJson(String sharedTokensJson) {
        this.sharedTokensJson = sharedTokensJson;
    }

    public String getSharedQuestsJson() {
        return sharedQuestsJson;
    }

    public void setSharedQuestsJson(String sharedQuestsJson) {
        this.sharedQuestsJson = sharedQuestsJson;
    }

    public String getMemoryMarkersJson() {
        return memoryMarkersJson;
    }

    public void setMemoryMarkersJson(String memoryMarkersJson) {
        this.memoryMarkersJson = memoryMarkersJson;
    }

    public String getHighwaysJson() {
        return highwaysJson;
    }

    public void setHighwaysJson(String highwaysJson) {
        this.highwaysJson = highwaysJson;
    }

    public String getStateHash() {
        return stateHash;
    }

    public void setStateHash(String stateHash) {
        this.stateHash = stateHash;
    }

    public String getPreviousStateHash() {
        return previousStateHash;
    }

    public void setPreviousStateHash(String previousStateHash) {
        this.previousStateHash = previousStateHash;
    }

    public Instant getLastBotProposalAt() {
        return lastBotProposalAt;
    }

    public void setLastBotProposalAt(Instant lastBotProposalAt) {
        this.lastBotProposalAt = lastBotProposalAt;
    }

    public Instant getLastBotTickAt() {
        return lastBotTickAt;
    }

    public void setLastBotTickAt(Instant lastBotTickAt) {
        this.lastBotTickAt = lastBotTickAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
