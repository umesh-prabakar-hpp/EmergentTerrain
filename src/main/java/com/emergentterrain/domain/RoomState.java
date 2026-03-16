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

    @Column(nullable = false, columnDefinition = "text")
    private String tilesJson;

    @Column(columnDefinition = "text")
    private String sharedTokensJson;

    @Column(columnDefinition = "text")
    private String sharedQuestsJson;

    @Column(columnDefinition = "text")
    private String memoryMarkersJson;

    @Column(columnDefinition = "text")
    private String highwaysJson;

    @Column(columnDefinition = "text")
    private String roadPressureJson;

    @Column(columnDefinition = "text")
    private String occupancyJson;

    private Instant occupancyUpdatedAt;

    private String stateHash;

    private String previousStateHash;

    private Instant lastBotProposalAt;
    private Instant lastBotProposalSweepAt;
    private Instant lastBotTickAt;
    private Instant lastCleanupAt;

    private Long totalChangeCount;
    private Integer changesSinceFlush;
    private Integer tileClearCount;
    private Integer tileForestCount;
    private Integer tileWaterCount;
    private Integer tileIndustryCount;
    private Integer tileHighwayCount;

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

    public String getRoadPressureJson() {
        return roadPressureJson;
    }

    public void setRoadPressureJson(String roadPressureJson) {
        this.roadPressureJson = roadPressureJson;
    }

    public String getOccupancyJson() {
        return occupancyJson;
    }

    public void setOccupancyJson(String occupancyJson) {
        this.occupancyJson = occupancyJson;
    }

    public Instant getOccupancyUpdatedAt() {
        return occupancyUpdatedAt;
    }

    public void setOccupancyUpdatedAt(Instant occupancyUpdatedAt) {
        this.occupancyUpdatedAt = occupancyUpdatedAt;
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

    public Instant getLastBotProposalSweepAt() {
        return lastBotProposalSweepAt;
    }

    public void setLastBotProposalSweepAt(Instant lastBotProposalSweepAt) {
        this.lastBotProposalSweepAt = lastBotProposalSweepAt;
    }

    public Instant getLastBotTickAt() {
        return lastBotTickAt;
    }

    public void setLastBotTickAt(Instant lastBotTickAt) {
        this.lastBotTickAt = lastBotTickAt;
    }

    public Instant getLastCleanupAt() {
        return lastCleanupAt;
    }

    public void setLastCleanupAt(Instant lastCleanupAt) {
        this.lastCleanupAt = lastCleanupAt;
    }

    public Long getTotalChangeCount() {
        return totalChangeCount;
    }

    public void setTotalChangeCount(Long totalChangeCount) {
        this.totalChangeCount = totalChangeCount;
    }

    public Integer getChangesSinceFlush() {
        return changesSinceFlush;
    }

    public void setChangesSinceFlush(Integer changesSinceFlush) {
        this.changesSinceFlush = changesSinceFlush;
    }

    public Integer getTileClearCount() {
        return tileClearCount;
    }

    public void setTileClearCount(Integer tileClearCount) {
        this.tileClearCount = tileClearCount;
    }

    public Integer getTileForestCount() {
        return tileForestCount;
    }

    public void setTileForestCount(Integer tileForestCount) {
        this.tileForestCount = tileForestCount;
    }

    public Integer getTileWaterCount() {
        return tileWaterCount;
    }

    public void setTileWaterCount(Integer tileWaterCount) {
        this.tileWaterCount = tileWaterCount;
    }

    public Integer getTileIndustryCount() {
        return tileIndustryCount;
    }

    public void setTileIndustryCount(Integer tileIndustryCount) {
        this.tileIndustryCount = tileIndustryCount;
    }

    public Integer getTileHighwayCount() {
        return tileHighwayCount;
    }

    public void setTileHighwayCount(Integer tileHighwayCount) {
        this.tileHighwayCount = tileHighwayCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
