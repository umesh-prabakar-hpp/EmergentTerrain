package com.emergentterrain.web;

import java.time.Instant;
import java.util.List;

public class RoomCreateResponse {
    private String roomId;
    private String roomName;
    private String participantId;
    private int[][] tiles;
    private boolean[][] highways;
    private SharedTokens sharedTokens;
    private List<QuestItem> sharedQuests;
    private List<MemoryMarker> memoryMarkers;
    private List<RoomParticipantInfo> participants;
    private List<RoomBotInfo> bots;
    private List<RoomProposalInfo> proposals;
    private String turnOwnerParticipantId;
    private Instant turnExpiresAt;
    private Instant lockUntil;
    private int neighborRadius;
    private int minNeighbors;
    private int densityAreaSize;
    private int maxOccupants;
    private GovernmentSummary governmentSummary;

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

    public String getParticipantId() {
        return participantId;
    }

    public void setParticipantId(String participantId) {
        this.participantId = participantId;
    }

    public int[][] getTiles() {
        return tiles;
    }

    public void setTiles(int[][] tiles) {
        this.tiles = tiles;
    }

    public boolean[][] getHighways() {
        return highways;
    }

    public void setHighways(boolean[][] highways) {
        this.highways = highways;
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

    public List<RoomBotInfo> getBots() {
        return bots;
    }

    public void setBots(List<RoomBotInfo> bots) {
        this.bots = bots;
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

    public int getNeighborRadius() {
        return neighborRadius;
    }

    public void setNeighborRadius(int neighborRadius) {
        this.neighborRadius = neighborRadius;
    }

    public int getMinNeighbors() {
        return minNeighbors;
    }

    public void setMinNeighbors(int minNeighbors) {
        this.minNeighbors = minNeighbors;
    }

    public int getDensityAreaSize() {
        return densityAreaSize;
    }

    public void setDensityAreaSize(int densityAreaSize) {
        this.densityAreaSize = densityAreaSize;
    }

    public int getMaxOccupants() {
        return maxOccupants;
    }

    public void setMaxOccupants(int maxOccupants) {
        this.maxOccupants = maxOccupants;
    }

    public GovernmentSummary getGovernmentSummary() {
        return governmentSummary;
    }

    public void setGovernmentSummary(GovernmentSummary governmentSummary) {
        this.governmentSummary = governmentSummary;
    }
}
