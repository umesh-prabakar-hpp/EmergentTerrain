package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_participants")
public class RoomParticipant {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Room room;

    @ManyToOne(optional = false)
    private Profile profile;

    @Column(nullable = false)
    private String role = "GUEST";

    @Column(nullable = false)
    private String lens = "PATTERN";

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
