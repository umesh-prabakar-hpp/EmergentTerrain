package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profile_quest_state")
public class ProfileQuestState {
    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(optional = false)
    private Profile profile;

    @Column(nullable = false)
    private String season;

    @Column(nullable = false, columnDefinition = "text")
    private String questsJson;

    @Column(nullable = true)
    private String lastObserverPrompt;

    @Column(nullable = true)
    private Instant lastObserverAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public String getQuestsJson() {
        return questsJson;
    }

    public void setQuestsJson(String questsJson) {
        this.questsJson = questsJson;
    }

    public String getLastObserverPrompt() {
        return lastObserverPrompt;
    }

    public void setLastObserverPrompt(String lastObserverPrompt) {
        this.lastObserverPrompt = lastObserverPrompt;
    }

    public Instant getLastObserverAt() {
        return lastObserverAt;
    }

    public void setLastObserverAt(Instant lastObserverAt) {
        this.lastObserverAt = lastObserverAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
