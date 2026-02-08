package com.emergentterrain.web;

import java.util.Map;

public class GameStateResponse {
    private long seed;
    private int width;
    private int height;
    private int[][] tiles;
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

    public Map<String, Double> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Double> properties) {
        this.properties = properties;
    }
}
