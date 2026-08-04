package com.emergentterrain.web;

import java.time.Instant;

public class GovernmentElectionSummary {
    private String nextScope;
    private Instant nextElectionAt;
    private Instant lastHeldAt;
    private String lastWinnerParty;
    private double lastTurnout;
    private String lastSummary;

    public String getNextScope() {
        return nextScope;
    }

    public void setNextScope(String nextScope) {
        this.nextScope = nextScope;
    }

    public Instant getNextElectionAt() {
        return nextElectionAt;
    }

    public void setNextElectionAt(Instant nextElectionAt) {
        this.nextElectionAt = nextElectionAt;
    }

    public Instant getLastHeldAt() {
        return lastHeldAt;
    }

    public void setLastHeldAt(Instant lastHeldAt) {
        this.lastHeldAt = lastHeldAt;
    }

    public String getLastWinnerParty() {
        return lastWinnerParty;
    }

    public void setLastWinnerParty(String lastWinnerParty) {
        this.lastWinnerParty = lastWinnerParty;
    }

    public double getLastTurnout() {
        return lastTurnout;
    }

    public void setLastTurnout(double lastTurnout) {
        this.lastTurnout = lastTurnout;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }
}
