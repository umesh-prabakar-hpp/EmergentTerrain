package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_proposals")
public class RoomProposal {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Room room;

    @ManyToOne(optional = false)
    private RoomParticipant proposer;

    @Lob
    @Column(nullable = false)
    private String tilesJson;

    @Lob
    private String deltaJson;

    @Column(nullable = false)
    private String status = "PENDING";

    private int approvals = 0;

    private int rejections = 0;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public RoomParticipant getProposer() {
        return proposer;
    }

    public void setProposer(RoomParticipant proposer) {
        this.proposer = proposer;
    }

    public String getTilesJson() {
        return tilesJson;
    }

    public void setTilesJson(String tilesJson) {
        this.tilesJson = tilesJson;
    }

    public String getDeltaJson() {
        return deltaJson;
    }

    public void setDeltaJson(String deltaJson) {
        this.deltaJson = deltaJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getApprovals() {
        return approvals;
    }

    public void setApprovals(int approvals) {
        this.approvals = approvals;
    }

    public int getRejections() {
        return rejections;
    }

    public void setRejections(int rejections) {
        this.rejections = rejections;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
