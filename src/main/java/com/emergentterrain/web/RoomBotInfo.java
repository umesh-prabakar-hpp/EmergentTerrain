package com.emergentterrain.web;

public class RoomBotInfo {
    private String id;
    private String type;
    private String displayName;
    private int x;
    private int y;
    private java.util.Map<String, Double> profile;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public java.util.Map<String, Double> getProfile() {
        return profile;
    }

    public void setProfile(java.util.Map<String, Double> profile) {
        this.profile = profile;
    }
}
