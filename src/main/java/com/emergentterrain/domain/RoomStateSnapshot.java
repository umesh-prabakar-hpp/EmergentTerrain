package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_state_history")
public class RoomStateSnapshot {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Room room;

    @Column(nullable = false)
    private Instant snapshotAt = Instant.now();

    private Long totalChangeCount;
    private Integer changesSinceFlush;
    private Integer tileClearCount;
    private Integer tileForestCount;
    private Integer tileWaterCount;
    private Integer tileIndustryCount;
    private Integer tileHighwayCount;

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(Instant snapshotAt) {
        this.snapshotAt = snapshotAt;
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
}
