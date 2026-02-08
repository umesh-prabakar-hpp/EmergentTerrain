package com.emergentterrain.web;

public class RegionInfo {
    private int id;
    private String name;
    private String meaning;
    private String explanation;
    private double forestDensity;
    private double clearDensity;
    private double waterDensity;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMeaning() {
        return meaning;
    }

    public void setMeaning(String meaning) {
        this.meaning = meaning;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public double getForestDensity() {
        return forestDensity;
    }

    public void setForestDensity(double forestDensity) {
        this.forestDensity = forestDensity;
    }

    public double getClearDensity() {
        return clearDensity;
    }

    public void setClearDensity(double clearDensity) {
        this.clearDensity = clearDensity;
    }

    public double getWaterDensity() {
        return waterDensity;
    }

    public void setWaterDensity(double waterDensity) {
        this.waterDensity = waterDensity;
    }
}
