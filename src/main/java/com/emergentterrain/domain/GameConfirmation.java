package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_confirmations")
public class GameConfirmation {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Profile profile;

    @Column(nullable = false, columnDefinition = "text")
    private String deltaJson;

    @Column(nullable = false, columnDefinition = "text")
    private String explanationText;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public String getDeltaJson() {
        return deltaJson;
    }

    public void setDeltaJson(String deltaJson) {
        this.deltaJson = deltaJson;
    }

    public String getExplanationText() {
        return explanationText;
    }

    public void setExplanationText(String explanationText) {
        this.explanationText = explanationText;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
