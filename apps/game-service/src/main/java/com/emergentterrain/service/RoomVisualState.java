package com.emergentterrain.service;

import com.emergentterrain.web.RoomProposalInfo;

import java.util.List;
import java.util.UUID;

public class RoomVisualState {
    private UUID roomId;
    private String stateHash;
    private String previousStateHash;
    private int[][] tiles;
    private boolean[][] highways;
    private List<RoomProposalInfo> proposals;

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
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

    public List<RoomProposalInfo> getProposals() {
        return proposals;
    }

    public void setProposals(List<RoomProposalInfo> proposals) {
        this.proposals = proposals;
    }
}
