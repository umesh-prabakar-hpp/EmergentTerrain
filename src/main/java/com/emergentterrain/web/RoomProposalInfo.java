package com.emergentterrain.web;

import java.time.Instant;

public class RoomProposalInfo {
    private String id;
    private String proposerName;
    private String status;
    private int approvals;
    private int rejections;
    private Instant createdAt;
    private int requiredVotes;
    private int neighborCount;
    private Instant expiresAt;
    private Integer anchorX;
    private Integer anchorY;
    private boolean botGenerated;
    private String botLabel;
    private Integer tileType;
    private boolean canVote;
    private boolean owner;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProposerName() {
        return proposerName;
    }

    public void setProposerName(String proposerName) {
        this.proposerName = proposerName;
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

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getRequiredVotes() {
        return requiredVotes;
    }

    public void setRequiredVotes(int requiredVotes) {
        this.requiredVotes = requiredVotes;
    }

    public int getNeighborCount() {
        return neighborCount;
    }

    public void setNeighborCount(int neighborCount) {
        this.neighborCount = neighborCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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

    public boolean isBotGenerated() {
        return botGenerated;
    }

    public void setBotGenerated(boolean botGenerated) {
        this.botGenerated = botGenerated;
    }

    public String getBotLabel() {
        return botLabel;
    }

    public void setBotLabel(String botLabel) {
        this.botLabel = botLabel;
    }

    public Integer getTileType() {
        return tileType;
    }

    public void setTileType(Integer tileType) {
        this.tileType = tileType;
    }

    public boolean isCanVote() {
        return canVote;
    }

    public void setCanVote(boolean canVote) {
        this.canVote = canVote;
    }

    public boolean isOwner() {
        return owner;
    }

    public void setOwner(boolean owner) {
        this.owner = owner;
    }
}
