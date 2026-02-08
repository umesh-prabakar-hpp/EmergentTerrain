package com.emergentterrain.web;

import java.time.Instant;
import java.util.List;

public class RoomStateResponse {
    private String roomId;
    private String roomName;
    private int[][] tiles;
    private SharedTokens sharedTokens;
    private List<QuestItem> sharedQuests;
    private List<MemoryMarker> memoryMarkers;
    private List<RoomParticipantInfo> participants;
    private List<RoomProposalInfo> proposals;
    private String turnOwnerParticipantId;
    private Instant turnExpiresAt;
    private Instant lockUntil;
    private String stateHash;
    private String previousStateHash;

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public int[][] getTiles() {
        return tiles;
    }

    public void setTiles(int[][] tiles) {
        this.tiles = tiles;
    }

    public SharedTokens getSharedTokens() {
        return sharedTokens;
    }

    public void setSharedTokens(SharedTokens sharedTokens) {
        this.sharedTokens = sharedTokens;
    }

    public List<QuestItem> getSharedQuests() {
        return sharedQuests;
    }

    public void setSharedQuests(List<QuestItem> sharedQuests) {
        this.sharedQuests = sharedQuests;
    }

    public List<MemoryMarker> getMemoryMarkers() {
        return memoryMarkers;
    }

    public void setMemoryMarkers(List<MemoryMarker> memoryMarkers) {
        this.memoryMarkers = memoryMarkers;
    }

    public List<RoomParticipantInfo> getParticipants() {
        return participants;
    }

    public void setParticipants(List<RoomParticipantInfo> participants) {
        this.participants = participants;
    }

    public List<RoomProposalInfo> getProposals() {
        return proposals;
    }

    public void setProposals(List<RoomProposalInfo> proposals) {
        this.proposals = proposals;
    }

    public String getTurnOwnerParticipantId() {
        return turnOwnerParticipantId;
    }

    public void setTurnOwnerParticipantId(String turnOwnerParticipantId) {
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
}
