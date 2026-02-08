package com.emergentterrain.web;

public class RoomResonanceResponse {
    private boolean[][] overlay;
    private double matchRatio;

    public boolean[][] getOverlay() {
        return overlay;
    }

    public void setOverlay(boolean[][] overlay) {
        this.overlay = overlay;
    }

    public double getMatchRatio() {
        return matchRatio;
    }

    public void setMatchRatio(double matchRatio) {
        this.matchRatio = matchRatio;
    }
}
