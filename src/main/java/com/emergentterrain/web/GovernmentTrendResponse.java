package com.emergentterrain.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GovernmentTrendResponse {
    private String roomId;
    private Instant generatedAt;
    private int windowMinutes;
    private List<GovernmentTrendPoint> points = new ArrayList<>();
    private List<GovernmentTrendEvent> events = new ArrayList<>();

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public List<GovernmentTrendPoint> getPoints() {
        return points;
    }

    public void setPoints(List<GovernmentTrendPoint> points) {
        this.points = points;
    }

    public List<GovernmentTrendEvent> getEvents() {
        return events;
    }

    public void setEvents(List<GovernmentTrendEvent> events) {
        this.events = events;
    }
}
