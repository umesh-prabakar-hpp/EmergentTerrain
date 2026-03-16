package com.emergentterrain.web;

public class PerfBotSimulationRequest {
    private int iterations = 1;
    private boolean clearGpuWorldCache = true;

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public boolean isClearGpuWorldCache() {
        return clearGpuWorldCache;
    }

    public void setClearGpuWorldCache(boolean clearGpuWorldCache) {
        this.clearGpuWorldCache = clearGpuWorldCache;
    }
}
