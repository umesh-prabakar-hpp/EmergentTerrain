package com.emergentterrain.web;

public class SharedTokens {
    private int insight;
    private int stability;
    private int energy;

    public SharedTokens() {
    }

    public SharedTokens(int insight, int stability, int energy) {
        this.insight = insight;
        this.stability = stability;
        this.energy = energy;
    }

    public int getInsight() {
        return insight;
    }

    public void setInsight(int insight) {
        this.insight = insight;
    }

    public int getStability() {
        return stability;
    }

    public void setStability(int stability) {
        this.stability = stability;
    }

    public int getEnergy() {
        return energy;
    }

    public void setEnergy(int energy) {
        this.energy = energy;
    }
}
