package com.emergentterrain.web;

public class ExplainRequest {
    private String token;
    private String streamId;
    private int x;
    private int y;
    private String tileType;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public String getTileType() { return tileType; }
    public void setTileType(String tileType) { this.tileType = tileType; }
}
