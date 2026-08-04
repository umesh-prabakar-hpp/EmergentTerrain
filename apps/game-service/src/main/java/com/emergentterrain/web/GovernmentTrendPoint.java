package com.emergentterrain.web;

import java.time.Instant;

public class GovernmentTrendPoint {
    private long tick;
    private Instant bucketStartAt;
    private Instant sampledAt;
    private int resolutionMinutes;
    private double federalTrust;
    private double federalServiceAccess;
    private double avgStateTrust;
    private double avgStateServiceAccess;
    private long revenue;
    private long education;
    private long health;
    private long infrastructure;
    private long welfare;
    private long justice;
    private long payroll;
    private int activeBillCount;
    private int activeExecutiveOrderCount;
    private int activeCourtCaseCount;
    private int activeAdministrativeCount;
    private int activeConflictCount;
    private double pressureIntensity;
    private double implementationScore;
    private int implementationBackfireCount;
    private int activeBotCount;
    private int petitioningBotCount;
    private int reviewBotCount;
    private int sanctionedBotCount;
    private double petitioningShare;
    private double sanctionedShare;
    private String federalExecutiveParty;
    private String federalLowerParty;
    private String federalUpperParty;
    private String federalChiefJusticeParty;

    public long getTick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    public Instant getBucketStartAt() {
        return bucketStartAt;
    }

    public void setBucketStartAt(Instant bucketStartAt) {
        this.bucketStartAt = bucketStartAt;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }

    public int getResolutionMinutes() {
        return resolutionMinutes;
    }

    public void setResolutionMinutes(int resolutionMinutes) {
        this.resolutionMinutes = resolutionMinutes;
    }

    public double getFederalTrust() {
        return federalTrust;
    }

    public void setFederalTrust(double federalTrust) {
        this.federalTrust = federalTrust;
    }

    public double getFederalServiceAccess() {
        return federalServiceAccess;
    }

    public void setFederalServiceAccess(double federalServiceAccess) {
        this.federalServiceAccess = federalServiceAccess;
    }

    public double getAvgStateTrust() {
        return avgStateTrust;
    }

    public void setAvgStateTrust(double avgStateTrust) {
        this.avgStateTrust = avgStateTrust;
    }

    public double getAvgStateServiceAccess() {
        return avgStateServiceAccess;
    }

    public void setAvgStateServiceAccess(double avgStateServiceAccess) {
        this.avgStateServiceAccess = avgStateServiceAccess;
    }

    public long getRevenue() {
        return revenue;
    }

    public void setRevenue(long revenue) {
        this.revenue = revenue;
    }

    public long getEducation() {
        return education;
    }

    public void setEducation(long education) {
        this.education = education;
    }

    public long getHealth() {
        return health;
    }

    public void setHealth(long health) {
        this.health = health;
    }

    public long getInfrastructure() {
        return infrastructure;
    }

    public void setInfrastructure(long infrastructure) {
        this.infrastructure = infrastructure;
    }

    public long getWelfare() {
        return welfare;
    }

    public void setWelfare(long welfare) {
        this.welfare = welfare;
    }

    public long getJustice() {
        return justice;
    }

    public void setJustice(long justice) {
        this.justice = justice;
    }

    public long getPayroll() {
        return payroll;
    }

    public void setPayroll(long payroll) {
        this.payroll = payroll;
    }

    public int getActiveBillCount() {
        return activeBillCount;
    }

    public void setActiveBillCount(int activeBillCount) {
        this.activeBillCount = activeBillCount;
    }

    public int getActiveExecutiveOrderCount() {
        return activeExecutiveOrderCount;
    }

    public void setActiveExecutiveOrderCount(int activeExecutiveOrderCount) {
        this.activeExecutiveOrderCount = activeExecutiveOrderCount;
    }

    public int getActiveCourtCaseCount() {
        return activeCourtCaseCount;
    }

    public void setActiveCourtCaseCount(int activeCourtCaseCount) {
        this.activeCourtCaseCount = activeCourtCaseCount;
    }

    public int getActiveAdministrativeCount() {
        return activeAdministrativeCount;
    }

    public void setActiveAdministrativeCount(int activeAdministrativeCount) {
        this.activeAdministrativeCount = activeAdministrativeCount;
    }

    public int getActiveConflictCount() {
        return activeConflictCount;
    }

    public void setActiveConflictCount(int activeConflictCount) {
        this.activeConflictCount = activeConflictCount;
    }

    public double getPressureIntensity() {
        return pressureIntensity;
    }

    public void setPressureIntensity(double pressureIntensity) {
        this.pressureIntensity = pressureIntensity;
    }

    public double getImplementationScore() {
        return implementationScore;
    }

    public void setImplementationScore(double implementationScore) {
        this.implementationScore = implementationScore;
    }

    public int getImplementationBackfireCount() {
        return implementationBackfireCount;
    }

    public void setImplementationBackfireCount(int implementationBackfireCount) {
        this.implementationBackfireCount = implementationBackfireCount;
    }

    public int getActiveBotCount() {
        return activeBotCount;
    }

    public void setActiveBotCount(int activeBotCount) {
        this.activeBotCount = activeBotCount;
    }

    public int getPetitioningBotCount() {
        return petitioningBotCount;
    }

    public void setPetitioningBotCount(int petitioningBotCount) {
        this.petitioningBotCount = petitioningBotCount;
    }

    public int getReviewBotCount() {
        return reviewBotCount;
    }

    public void setReviewBotCount(int reviewBotCount) {
        this.reviewBotCount = reviewBotCount;
    }

    public int getSanctionedBotCount() {
        return sanctionedBotCount;
    }

    public void setSanctionedBotCount(int sanctionedBotCount) {
        this.sanctionedBotCount = sanctionedBotCount;
    }

    public double getPetitioningShare() {
        return petitioningShare;
    }

    public void setPetitioningShare(double petitioningShare) {
        this.petitioningShare = petitioningShare;
    }

    public double getSanctionedShare() {
        return sanctionedShare;
    }

    public void setSanctionedShare(double sanctionedShare) {
        this.sanctionedShare = sanctionedShare;
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
}
