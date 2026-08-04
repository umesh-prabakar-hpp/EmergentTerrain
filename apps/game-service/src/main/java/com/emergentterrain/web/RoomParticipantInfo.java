package com.emergentterrain.web;

public class RoomParticipantInfo {
    private String id;
    private String profileId;
    private String displayName;
    private String role;
    private String lens;
    private int x;
    private int y;
    private int missedVotes;
    private java.time.Instant lockedUntil;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLens() {
        return lens;
    }

    public void setLens(String lens) {
        this.lens = lens;
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

    public int getMissedVotes() {
        return missedVotes;
    }

    public void setMissedVotes(int missedVotes) {
        this.missedVotes = missedVotes;
    }

    public java.time.Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(java.time.Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
