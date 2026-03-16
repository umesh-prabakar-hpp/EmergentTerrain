package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_bots")
public class RoomBot {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private Room room;

    @Column(nullable = false)
    private String type;

    private String lastPrompt;

    private Instant lastRunAt;

    private Instant lastProposalAt;

    private Integer x;

    private Integer y;

    @Column(columnDefinition = "text")
    private String profileJson;

    @Column(columnDefinition = "text")
    private String stateJson;

    @Column(columnDefinition = "text")
    private String civicStateJson;

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLastPrompt() {
        return lastPrompt;
    }

    public void setLastPrompt(String lastPrompt) {
        this.lastPrompt = lastPrompt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Instant getLastProposalAt() {
        return lastProposalAt;
    }

    public void setLastProposalAt(Instant lastProposalAt) {
        this.lastProposalAt = lastProposalAt;
    }

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    public String getProfileJson() {
        return profileJson;
    }

    public void setProfileJson(String profileJson) {
        this.profileJson = profileJson;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public String getCivicStateJson() {
        return civicStateJson;
    }

    public void setCivicStateJson(String civicStateJson) {
        this.civicStateJson = civicStateJson;
    }
}
