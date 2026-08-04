package com.emergentterrain.web;

import java.time.Instant;

public class ProfileSelectItem {
    private String profileId;
    private String token;
    private Instant createdAt;

    public ProfileSelectItem(String profileId, String token, Instant createdAt) {
        this.profileId = profileId;
        this.token = token;
        this.createdAt = createdAt;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getToken() {
        return token;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
