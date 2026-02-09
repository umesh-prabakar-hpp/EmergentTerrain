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

    @ManyToOne(optional = true)
    @JoinColumn(nullable = true)
    private RoomParticipant proposer;

    @ManyToOne(optional = true)
    @JoinColumn(nullable = true)
    private RoomBot botProposer;

    @Lob
    @Column(nullable = false)
    private String tilesJson;

    @Lob
    private String deltaJson;

    @Lob
    private String neighborIdsJson;

    @Column(nullable = false)
    private String status = "PENDING";

    private int approvals = 0;

    private int rejections = 0;

    private Integer anchorX;

    private Integer anchorY;

    private Boolean botGenerated;

    private String botLabel;

    private int requiredVotes = 0;

    private Instant expiresAt;

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

    public RoomBot getBotProposer() {
        return botProposer;
    }

    public void setBotProposer(RoomBot botProposer) {
        this.botProposer = botProposer;
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

    public String getNeighborIdsJson() {
        return neighborIdsJson;
    }

    public void setNeighborIdsJson(String neighborIdsJson) {
        this.neighborIdsJson = neighborIdsJson;
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

    public Integer getAnchorX() {
        return anchorX;
    }

    public void setAnchorX(Integer anchorX) {
        this.anchorX = anchorX;
    }

    public Integer getAnchorY() {
        return anchorY;
    }

    public void setAnchorY(Integer anchorY) {
        this.anchorY = anchorY;
    }

    public Boolean isBotGenerated() {
        return botGenerated;
    }

    public void setBotGenerated(Boolean botGenerated) {
        this.botGenerated = botGenerated;
    }

    public String getBotLabel() {
        return botLabel;
    }

    public void setBotLabel(String botLabel) {
        this.botLabel = botLabel;
    }

    public int getRequiredVotes() {
        return requiredVotes;
    }

    public void setRequiredVotes(int requiredVotes) {
        this.requiredVotes = requiredVotes;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
