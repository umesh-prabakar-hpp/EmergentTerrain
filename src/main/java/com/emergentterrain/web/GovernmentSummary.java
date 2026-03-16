package com.emergentterrain.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GovernmentSummary {
    private String constitutionVersion;
    private long tick;
    private Instant nextElectionAt;
    private String federalExecutiveParty;
    private String federalLowerParty;
    private String federalUpperParty;
    private String federalChiefJusticeParty;
    private double federalTrust;
    private long federalRevenue;
    private long federalInfrastructureBudget;
    private int stateCount;
    private int activeBillCount;
    private List<String> activeBills = new ArrayList<>();
    private List<String> partySummaries = new ArrayList<>();

    public String getConstitutionVersion() {
        return constitutionVersion;
    }

    public void setConstitutionVersion(String constitutionVersion) {
        this.constitutionVersion = constitutionVersion;
    }

    public long getTick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    public Instant getNextElectionAt() {
        return nextElectionAt;
    }

    public void setNextElectionAt(Instant nextElectionAt) {
        this.nextElectionAt = nextElectionAt;
    }

    public String getFederalExecutiveParty() {
        return federalExecutiveParty;
    }

    public void setFederalExecutiveParty(String federalExecutiveParty) {
        this.federalExecutiveParty = federalExecutiveParty;
    }

    public String getFederalLowerParty() {
        return federalLowerParty;
    }

    public void setFederalLowerParty(String federalLowerParty) {
        this.federalLowerParty = federalLowerParty;
    }

    public String getFederalUpperParty() {
        return federalUpperParty;
    }

    public void setFederalUpperParty(String federalUpperParty) {
        this.federalUpperParty = federalUpperParty;
    }

    public String getFederalChiefJusticeParty() {
        return federalChiefJusticeParty;
    }

    public void setFederalChiefJusticeParty(String federalChiefJusticeParty) {
        this.federalChiefJusticeParty = federalChiefJusticeParty;
    }

    public double getFederalTrust() {
        return federalTrust;
    }

    public void setFederalTrust(double federalTrust) {
        this.federalTrust = federalTrust;
    }

    public long getFederalRevenue() {
        return federalRevenue;
    }

    public void setFederalRevenue(long federalRevenue) {
        this.federalRevenue = federalRevenue;
    }

    public long getFederalInfrastructureBudget() {
        return federalInfrastructureBudget;
    }

    public void setFederalInfrastructureBudget(long federalInfrastructureBudget) {
        this.federalInfrastructureBudget = federalInfrastructureBudget;
    }

    public int getStateCount() {
        return stateCount;
    }

    public void setStateCount(int stateCount) {
        this.stateCount = stateCount;
    }

    public int getActiveBillCount() {
        return activeBillCount;
    }

    public void setActiveBillCount(int activeBillCount) {
        this.activeBillCount = activeBillCount;
    }

    public List<String> getActiveBills() {
        return activeBills;
    }

    public void setActiveBills(List<String> activeBills) {
        this.activeBills = activeBills;
    }

    public List<String> getPartySummaries() {
        return partySummaries;
    }

    public void setPartySummaries(List<String> partySummaries) {
        this.partySummaries = partySummaries;
    }
}
