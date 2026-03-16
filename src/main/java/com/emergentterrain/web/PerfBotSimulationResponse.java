package com.emergentterrain.web;

public class PerfBotSimulationResponse {
    private int iterations;
    private int botCount;
    private long totalMs;
    private double averageMs;
    private long minMs;
    private long maxMs;

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public int getBotCount() {
        return botCount;
    }

    public void setBotCount(int botCount) {
        this.botCount = botCount;
    }

    public long getTotalMs() {
        return totalMs;
    }

    public void setTotalMs(long totalMs) {
        this.totalMs = totalMs;
    }

    public double getAverageMs() {
        return averageMs;
    }

    public void setAverageMs(double averageMs) {
        this.averageMs = averageMs;
    }

    public long getMinMs() {
        return minMs;
    }

    public void setMinMs(long minMs) {
        this.minMs = minMs;
    }

    public long getMaxMs() {
        return maxMs;
    }

    public void setMaxMs(long maxMs) {
        this.maxMs = maxMs;
    }
}
