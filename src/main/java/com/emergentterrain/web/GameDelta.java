package com.emergentterrain.web;

public class GameDelta {
    private int forestTilesAdded;
    private int forestTilesRemoved;
    private int clearTilesAdded;

    public int getForestTilesAdded() {
        return forestTilesAdded;
    }

    public void setForestTilesAdded(int forestTilesAdded) {
        this.forestTilesAdded = forestTilesAdded;
    }

    public int getForestTilesRemoved() {
        return forestTilesRemoved;
    }

    public void setForestTilesRemoved(int forestTilesRemoved) {
        this.forestTilesRemoved = forestTilesRemoved;
    }

    public int getClearTilesAdded() {
        return clearTilesAdded;
    }

    public void setClearTilesAdded(int clearTilesAdded) {
        this.clearTilesAdded = clearTilesAdded;
    }
}
