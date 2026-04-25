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
    private int activeExecutiveOrderCount;
    private int activeCourtCaseCount;
    private int activeBotCount;
    private int petitioningBotCount;
    private int reviewBotCount;
    private int sanctionedBotCount;
    private double petitioningShare;
    private double sanctionedShare;
    private List<String> activeBills = new ArrayList<>();
    private List<String> executiveOrders = new ArrayList<>();
    private List<String> courtCases = new ArrayList<>();
    private List<GovernmentActionSummary> actionItems = new ArrayList<>();
    private List<GovernmentActionSummary> institutionItems = new ArrayList<>();
    private List<GovernmentActionSummary> precedentItems = new ArrayList<>();
    private List<GovernmentActionSummary> administrativeItems = new ArrayList<>();
    private List<GovernmentActionSummary> implementationItems = new ArrayList<>();
    private List<GovernmentActionSummary> pressureItems = new ArrayList<>();
    private List<GovernmentActionSummary> conflictItems = new ArrayList<>();
    private List<GovernmentActionSummary> actorItems = new ArrayList<>();
    private List<GovernmentActionSummary> historyItems = new ArrayList<>();
    private List<GovernmentActionSummary> civicLegalItems = new ArrayList<>();
    private GovernmentBudgetSummary federalBudget;
    private List<GovernmentStateMiniSummary> stateSummaries = new ArrayList<>();
    private GovernmentElectionSummary electionSummary;
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

    public List<String> getActiveBills() {
        return activeBills;
    }

    public void setActiveBills(List<String> activeBills) {
        this.activeBills = activeBills;
    }

    public List<String> getExecutiveOrders() {
        return executiveOrders;
    }

    public void setExecutiveOrders(List<String> executiveOrders) {
        this.executiveOrders = executiveOrders;
    }

    public List<String> getCourtCases() {
        return courtCases;
    }

    public void setCourtCases(List<String> courtCases) {
        this.courtCases = courtCases;
    }

    public List<GovernmentActionSummary> getActionItems() {
        return actionItems;
    }

    public void setActionItems(List<GovernmentActionSummary> actionItems) {
        this.actionItems = actionItems;
    }

    public List<GovernmentActionSummary> getInstitutionItems() {
        return institutionItems;
    }

    public void setInstitutionItems(List<GovernmentActionSummary> institutionItems) {
        this.institutionItems = institutionItems;
    }

    public List<GovernmentActionSummary> getPrecedentItems() {
        return precedentItems;
    }

    public void setPrecedentItems(List<GovernmentActionSummary> precedentItems) {
        this.precedentItems = precedentItems;
    }

    public List<GovernmentActionSummary> getAdministrativeItems() {
        return administrativeItems;
    }

    public void setAdministrativeItems(List<GovernmentActionSummary> administrativeItems) {
        this.administrativeItems = administrativeItems;
    }

    public List<GovernmentActionSummary> getImplementationItems() {
        return implementationItems;
    }

    public void setImplementationItems(List<GovernmentActionSummary> implementationItems) {
        this.implementationItems = implementationItems;
    }

    public List<GovernmentActionSummary> getPressureItems() {
        return pressureItems;
    }

    public void setPressureItems(List<GovernmentActionSummary> pressureItems) {
        this.pressureItems = pressureItems;
    }

    public List<GovernmentActionSummary> getConflictItems() {
        return conflictItems;
    }

    public void setConflictItems(List<GovernmentActionSummary> conflictItems) {
        this.conflictItems = conflictItems;
    }

    public List<GovernmentActionSummary> getActorItems() {
        return actorItems;
    }

    public void setActorItems(List<GovernmentActionSummary> actorItems) {
        this.actorItems = actorItems;
    }

    public List<GovernmentActionSummary> getHistoryItems() {
        return historyItems;
    }

    public void setHistoryItems(List<GovernmentActionSummary> historyItems) {
        this.historyItems = historyItems;
    }

    public List<GovernmentActionSummary> getCivicLegalItems() {
        return civicLegalItems;
    }

    public void setCivicLegalItems(List<GovernmentActionSummary> civicLegalItems) {
        this.civicLegalItems = civicLegalItems;
    }

    public GovernmentBudgetSummary getFederalBudget() {
        return federalBudget;
    }

    public void setFederalBudget(GovernmentBudgetSummary federalBudget) {
        this.federalBudget = federalBudget;
    }

    public List<GovernmentStateMiniSummary> getStateSummaries() {
        return stateSummaries;
    }

    public void setStateSummaries(List<GovernmentStateMiniSummary> stateSummaries) {
        this.stateSummaries = stateSummaries;
    }

    public GovernmentElectionSummary getElectionSummary() {
        return electionSummary;
    }

    public void setElectionSummary(GovernmentElectionSummary electionSummary) {
        this.electionSummary = electionSummary;
    }

    public List<String> getPartySummaries() {
        return partySummaries;
    }

    public void setPartySummaries(List<String> partySummaries) {
        this.partySummaries = partySummaries;
    }
}
