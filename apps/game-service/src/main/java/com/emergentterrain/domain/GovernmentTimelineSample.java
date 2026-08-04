package com.emergentterrain.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "government_timeline_sample",
    indexes = {
        @Index(name = "idx_gov_timeline_sample_room_time", columnList = "room_id, bucketStartAt"),
        @Index(name = "idx_gov_timeline_sample_bucket_time", columnList = "bucketMinutes, bucketStartAt"),
        @Index(name = "idx_gov_timeline_sample_room_tick", columnList = "room_id, tick")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_gov_timeline_sample_room_bucket", columnNames = {"room_id", "bucketMinutes", "bucketStartAt"})
    }
)
public class GovernmentTimelineSample {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Room room;

    @Column(nullable = false)
    private long tick;

    @Column(nullable = false)
    private Instant sampledAt;

    @Column(nullable = false)
    private Instant bucketStartAt;

    @Column(nullable = false)
    private int bucketMinutes;

    @Column(nullable = false)
    private double federalTrust;

    @Column(nullable = false)
    private double federalServiceAccess;

    @Column(nullable = false)
    private double avgStateTrust;

    @Column(nullable = false)
    private double avgStateServiceAccess;

    @Column(nullable = false)
    private long federalRevenue;

    @Column(nullable = false)
    private long federalEducation;

    @Column(nullable = false)
    private long federalHealth;

    @Column(nullable = false)
    private long federalInfrastructure;

    @Column(nullable = false)
    private long federalWelfare;

    @Column(nullable = false)
    private long federalJustice;

    @Column(nullable = false)
    private long federalPayroll;

    @Column(nullable = false)
    private int activeBillCount;

    @Column(nullable = false)
    private int activeExecutiveOrderCount;

    @Column(nullable = false)
    private int activeCourtCaseCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int activeAdministrativeCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int activeConflictCount;

    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    private double pressureIntensity;

    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    private double implementationScore;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int implementationBackfireCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int activeBotCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int petitioningBotCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int reviewBotCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int sanctionedBotCount;

    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    private double petitioningShare;

    @Column(nullable = false, columnDefinition = "double precision default 0.0")
    private double sanctionedShare;

    @Column(length = 128)
    private String federalExecutiveParty;

    @Column(length = 128)
    private String federalLowerParty;

    @Column(length = 128)
    private String federalUpperParty;

    @Column(length = 128)
    private String federalChiefJusticeParty;

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public long getTick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }

    public Instant getBucketStartAt() {
        return bucketStartAt;
    }

    public void setBucketStartAt(Instant bucketStartAt) {
        this.bucketStartAt = bucketStartAt;
    }

    public int getBucketMinutes() {
        return bucketMinutes;
    }

    public void setBucketMinutes(int bucketMinutes) {
        this.bucketMinutes = bucketMinutes;
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

    public long getFederalRevenue() {
        return federalRevenue;
    }

    public void setFederalRevenue(long federalRevenue) {
        this.federalRevenue = federalRevenue;
    }

    public long getFederalEducation() {
        return federalEducation;
    }

    public void setFederalEducation(long federalEducation) {
        this.federalEducation = federalEducation;
    }

    public long getFederalHealth() {
        return federalHealth;
    }

    public void setFederalHealth(long federalHealth) {
        this.federalHealth = federalHealth;
    }

    public long getFederalInfrastructure() {
        return federalInfrastructure;
    }

    public void setFederalInfrastructure(long federalInfrastructure) {
        this.federalInfrastructure = federalInfrastructure;
    }

    public long getFederalWelfare() {
        return federalWelfare;
    }

    public void setFederalWelfare(long federalWelfare) {
        this.federalWelfare = federalWelfare;
    }

    public long getFederalJustice() {
        return federalJustice;
    }

    public void setFederalJustice(long federalJustice) {
        this.federalJustice = federalJustice;
    }

    public long getFederalPayroll() {
        return federalPayroll;
    }

    public void setFederalPayroll(long federalPayroll) {
        this.federalPayroll = federalPayroll;
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
