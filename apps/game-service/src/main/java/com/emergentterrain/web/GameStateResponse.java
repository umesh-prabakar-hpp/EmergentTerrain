package com.emergentterrain.web;

import java.util.Map;
import java.util.List;

public class GameStateResponse {
    private long seed;
    private int width;
    private int height;
    private int[][] tiles;
    private boolean[][] highways;
    private int[][] regionIds;
    private boolean[][] fog;
    private List<RegionInfo> regions;
    private PlayerPosition player;
    private List<SnapshotInfo> snapshots;
    private QuestStateResponse questState;
    private Map<String, Double> properties;

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int[][] getTiles() {
        return tiles;
    }

    public void setTiles(int[][] tiles) {
        this.tiles = tiles;
    }

    public boolean[][] getHighways() {
        return highways;
    }

    public void setHighways(boolean[][] highways) {
        this.highways = highways;
    }

    public int[][] getRegionIds() {
        return regionIds;
    }

    public void setRegionIds(int[][] regionIds) {
        this.regionIds = regionIds;
    }

    public boolean[][] getFog() {
        return fog;
    }

    public void setFog(boolean[][] fog) {
        this.fog = fog;
    }

    public List<RegionInfo> getRegions() {
        return regions;
    }

    public void setRegions(List<RegionInfo> regions) {
        this.regions = regions;
    }

    public PlayerPosition getPlayer() {
        return player;
    }

    public void setPlayer(PlayerPosition player) {
        this.player = player;
    }

    public List<SnapshotInfo> getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(List<SnapshotInfo> snapshots) {
        this.snapshots = snapshots;
    }

    public QuestStateResponse getQuestState() {
        return questState;
    }

    public void setQuestState(QuestStateResponse questState) {
        this.questState = questState;
    }

    public Map<String, Double> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Double> properties) {
        this.properties = properties;
    }
}
