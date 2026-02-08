package com.emergentterrain.web;

public class RoomProposalRequest {
    private String token;
    private int[][] tiles;
    private Object delta;
    private String note;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int[][] getTiles() {
        return tiles;
    }

    public void setTiles(int[][] tiles) {
        this.tiles = tiles;
    }

    public Object getDelta() {
        return delta;
    }

    public void setDelta(Object delta) {
        this.delta = delta;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
