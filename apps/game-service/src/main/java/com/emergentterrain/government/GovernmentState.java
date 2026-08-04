package com.emergentterrain.government;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GovernmentState {
    private String constitutionVersion;
    private long tick;
    private Instant lastTickAt;
    private Instant nextElectionAt;
    private Instant lastBillAt;
    private int nextBillSequence;
    private int nextElectionSequence;
    private int nextEventSequence;
    private int nextOrderSequence;
    private int nextCourtSequence;
    private int nextPrecedentSequence;
    private int nextActorSequence;
    private int nextAgencySequence;
    private int nextPressureSequence;
    private int nextConflictSequence;
    private int nextImplementationSequence;
    private int nextHistoricalSequence;
    private List<ConstitutionRule> constitutionalRules = new ArrayList<>();
    private GovernmentNode federal = new GovernmentNode();
    private List<GovernmentNode> states = new ArrayList<>();
    private List<PoliticalParty> parties = new ArrayList<>();
    private List<InstitutionProfile> institutions = new ArrayList<>();
    private List<GovernmentBill> bills = new ArrayList<>();
    private List<ElectionRecord> elections = new ArrayList<>();
    private List<ExecutiveOrder> executiveOrders = new ArrayList<>();
    private List<CourtCase> courtCases = new ArrayList<>();
    private List<LegalPrecedent> precedents = new ArrayList<>();
    private List<CivicActor> actors = new ArrayList<>();
    private List<AdministrativeAction> administrativeActions = new ArrayList<>();
    private List<PoliticalPressure> pressureEvents = new ArrayList<>();
    private List<FederalStateConflict> conflicts = new ArrayList<>();
    private List<ImplementationOutcome> implementationOutcomes = new ArrayList<>();
    private List<HistoricalMarker> historicalMarkers = new ArrayList<>();
    private List<String> policyHints = new ArrayList<>();
    private List<String> researchHints = new ArrayList<>();
    private List<String> civicGoals = new ArrayList<>();
    private Map<String, Double> tileNudges = new HashMap<>();
    private int activeBotCount;
    private int petitioningBotCount;
    private int reviewBotCount;
    private int sanctionedBotCount;
    private double petitioningShare;
    private double sanctionedShare;

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

    public Instant getLastTickAt() {
        return lastTickAt;
    }

    public void setLastTickAt(Instant lastTickAt) {
        this.lastTickAt = lastTickAt;
    }

    public Instant getNextElectionAt() {
        return nextElectionAt;
    }

    public void setNextElectionAt(Instant nextElectionAt) {
        this.nextElectionAt = nextElectionAt;
    }

    public Instant getLastBillAt() {
        return lastBillAt;
    }

    public void setLastBillAt(Instant lastBillAt) {
        this.lastBillAt = lastBillAt;
    }

    public int getNextBillSequence() {
        return nextBillSequence;
    }

    public void setNextBillSequence(int nextBillSequence) {
        this.nextBillSequence = nextBillSequence;
    }

    public int getNextElectionSequence() {
        return nextElectionSequence;
    }

    public void setNextElectionSequence(int nextElectionSequence) {
        this.nextElectionSequence = nextElectionSequence;
    }

    public int getNextEventSequence() {
        return nextEventSequence;
    }

    public void setNextEventSequence(int nextEventSequence) {
        this.nextEventSequence = nextEventSequence;
    }

    public int getNextOrderSequence() {
        return nextOrderSequence;
    }

    public void setNextOrderSequence(int nextOrderSequence) {
        this.nextOrderSequence = nextOrderSequence;
    }

    public int getNextCourtSequence() {
        return nextCourtSequence;
    }

    public void setNextCourtSequence(int nextCourtSequence) {
        this.nextCourtSequence = nextCourtSequence;
    }

    public int getNextPrecedentSequence() {
        return nextPrecedentSequence;
    }

    public void setNextPrecedentSequence(int nextPrecedentSequence) {
        this.nextPrecedentSequence = nextPrecedentSequence;
    }

    public int getNextActorSequence() {
        return nextActorSequence;
    }

    public void setNextActorSequence(int nextActorSequence) {
        this.nextActorSequence = nextActorSequence;
    }

    public int getNextAgencySequence() {
        return nextAgencySequence;
    }

    public void setNextAgencySequence(int nextAgencySequence) {
        this.nextAgencySequence = nextAgencySequence;
    }

    public int getNextPressureSequence() {
        return nextPressureSequence;
    }

    public void setNextPressureSequence(int nextPressureSequence) {
        this.nextPressureSequence = nextPressureSequence;
    }

    public int getNextConflictSequence() {
        return nextConflictSequence;
    }

    public void setNextConflictSequence(int nextConflictSequence) {
        this.nextConflictSequence = nextConflictSequence;
    }

    public int getNextImplementationSequence() {
        return nextImplementationSequence;
    }

    public void setNextImplementationSequence(int nextImplementationSequence) {
        this.nextImplementationSequence = nextImplementationSequence;
    }

    public int getNextHistoricalSequence() {
        return nextHistoricalSequence;
    }

    public void setNextHistoricalSequence(int nextHistoricalSequence) {
        this.nextHistoricalSequence = nextHistoricalSequence;
    }

    public List<ConstitutionRule> getConstitutionalRules() {
        return constitutionalRules;
    }

    public void setConstitutionalRules(List<ConstitutionRule> constitutionalRules) {
        this.constitutionalRules = constitutionalRules;
    }

    public GovernmentNode getFederal() {
        return federal;
    }

    public void setFederal(GovernmentNode federal) {
        this.federal = federal;
    }

    public List<GovernmentNode> getStates() {
        return states;
    }

    public void setStates(List<GovernmentNode> states) {
        this.states = states;
    }

    public List<PoliticalParty> getParties() {
        return parties;
    }

    public void setParties(List<PoliticalParty> parties) {
        this.parties = parties;
    }

    public List<InstitutionProfile> getInstitutions() {
        return institutions;
    }

    public void setInstitutions(List<InstitutionProfile> institutions) {
        this.institutions = institutions;
    }

    public List<GovernmentBill> getBills() {
        return bills;
    }

    public void setBills(List<GovernmentBill> bills) {
        this.bills = bills;
    }

    public List<ElectionRecord> getElections() {
        return elections;
    }

    public void setElections(List<ElectionRecord> elections) {
        this.elections = elections;
    }

    public List<ExecutiveOrder> getExecutiveOrders() {
        return executiveOrders;
    }

    public void setExecutiveOrders(List<ExecutiveOrder> executiveOrders) {
        this.executiveOrders = executiveOrders;
    }

    public List<CourtCase> getCourtCases() {
        return courtCases;
    }

    public void setCourtCases(List<CourtCase> courtCases) {
        this.courtCases = courtCases;
    }

    public List<LegalPrecedent> getPrecedents() {
        return precedents;
    }

    public void setPrecedents(List<LegalPrecedent> precedents) {
        this.precedents = precedents;
    }

    public List<CivicActor> getActors() {
        return actors;
    }

    public void setActors(List<CivicActor> actors) {
        this.actors = actors;
    }

    public List<AdministrativeAction> getAdministrativeActions() {
        return administrativeActions;
    }

    public void setAdministrativeActions(List<AdministrativeAction> administrativeActions) {
        this.administrativeActions = administrativeActions;
    }

    public List<PoliticalPressure> getPressureEvents() {
        return pressureEvents;
    }

    public void setPressureEvents(List<PoliticalPressure> pressureEvents) {
        this.pressureEvents = pressureEvents;
    }

    public List<FederalStateConflict> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<FederalStateConflict> conflicts) {
        this.conflicts = conflicts;
    }

    public List<ImplementationOutcome> getImplementationOutcomes() {
        return implementationOutcomes;
    }

    public void setImplementationOutcomes(List<ImplementationOutcome> implementationOutcomes) {
        this.implementationOutcomes = implementationOutcomes;
    }

    public List<HistoricalMarker> getHistoricalMarkers() {
        return historicalMarkers;
    }

    public void setHistoricalMarkers(List<HistoricalMarker> historicalMarkers) {
        this.historicalMarkers = historicalMarkers;
    }

    public List<String> getPolicyHints() {
        return policyHints;
    }

    public void setPolicyHints(List<String> policyHints) {
        this.policyHints = policyHints != null ? policyHints : new ArrayList<>();
    }

    public List<String> getResearchHints() {
        return researchHints;
    }

    public void setResearchHints(List<String> researchHints) {
        this.researchHints = researchHints != null ? researchHints : new ArrayList<>();
    }

    public List<String> getCivicGoals() {
        return civicGoals;
    }

    public void setCivicGoals(List<String> civicGoals) {
        this.civicGoals = civicGoals != null ? civicGoals : new ArrayList<>();
    }

    public Map<String, Double> getTileNudges() {
        return tileNudges;
    }

    public void setTileNudges(Map<String, Double> tileNudges) {
        this.tileNudges = tileNudges != null ? tileNudges : new HashMap<>();
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

    public static class ConstitutionRule {
        private String ruleId;
        private String level;
        private String articleOrAmendment;
        private String title;
        private String category;
        private String protectionType;
        private String trigger;
        private String enforcement;
        private List<String> affectedSystems = new ArrayList<>();

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getArticleOrAmendment() {
            return articleOrAmendment;
        }

        public void setArticleOrAmendment(String articleOrAmendment) {
            this.articleOrAmendment = articleOrAmendment;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getProtectionType() {
            return protectionType;
        }

        public void setProtectionType(String protectionType) {
            this.protectionType = protectionType;
        }

        public String getTrigger() {
            return trigger;
        }

        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }

        public String getEnforcement() {
            return enforcement;
        }

        public void setEnforcement(String enforcement) {
            this.enforcement = enforcement;
        }

        public List<String> getAffectedSystems() {
            return affectedSystems;
        }

        public void setAffectedSystems(List<String> affectedSystems) {
            this.affectedSystems = affectedSystems;
        }
    }

    public static class BudgetState {
        private String budgetId;
        private String level;
        private String jurisdictionId;
        private long revenue;
        private long education;
        private long health;
        private long infrastructure;
        private long welfare;
        private long justice;
        private long payroll;

        public String getBudgetId() {
            return budgetId;
        }

        public void setBudgetId(String budgetId) {
            this.budgetId = budgetId;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
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
    }

    public static class GovernmentNode {
        private String nodeId;
        private String level;
        private String displayName;
        private String districtingMode;
        private int districtCount;
        private String lowerChamberPartyId;
        private String upperChamberPartyId;
        private String executivePartyId;
        private String chiefJusticePartyId;
        private double publicTrust;
        private double serviceAccess;
        private BudgetState budget = new BudgetState();

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDistrictingMode() {
            return districtingMode;
        }

        public void setDistrictingMode(String districtingMode) {
            this.districtingMode = districtingMode;
        }

        public int getDistrictCount() {
            return districtCount;
        }

        public void setDistrictCount(int districtCount) {
            this.districtCount = districtCount;
        }

        public String getLowerChamberPartyId() {
            return lowerChamberPartyId;
        }

        public void setLowerChamberPartyId(String lowerChamberPartyId) {
            this.lowerChamberPartyId = lowerChamberPartyId;
        }

        public String getUpperChamberPartyId() {
            return upperChamberPartyId;
        }

        public void setUpperChamberPartyId(String upperChamberPartyId) {
            this.upperChamberPartyId = upperChamberPartyId;
        }

        public String getExecutivePartyId() {
            return executivePartyId;
        }

        public void setExecutivePartyId(String executivePartyId) {
            this.executivePartyId = executivePartyId;
        }

        public String getChiefJusticePartyId() {
            return chiefJusticePartyId;
        }

        public void setChiefJusticePartyId(String chiefJusticePartyId) {
            this.chiefJusticePartyId = chiefJusticePartyId;
        }

        public double getPublicTrust() {
            return publicTrust;
        }

        public void setPublicTrust(double publicTrust) {
            this.publicTrust = publicTrust;
        }

        public double getServiceAccess() {
            return serviceAccess;
        }

        public void setServiceAccess(double serviceAccess) {
            this.serviceAccess = serviceAccess;
        }

        public BudgetState getBudget() {
            return budget;
        }

        public void setBudget(BudgetState budget) {
            this.budget = budget;
        }
    }

    public static class PoliticalParty {
        private String partyId;
        private String name;
        private double liberty;
        private double order;
        private double welfare;
        private double growth;
        private int memberCount;
        private double influence;

        public String getPartyId() {
            return partyId;
        }

        public void setPartyId(String partyId) {
            this.partyId = partyId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getLiberty() {
            return liberty;
        }

        public void setLiberty(double liberty) {
            this.liberty = liberty;
        }

        public double getOrder() {
            return order;
        }

        public void setOrder(double order) {
            this.order = order;
        }

        public double getWelfare() {
            return welfare;
        }

        public void setWelfare(double welfare) {
            this.welfare = welfare;
        }

        public double getGrowth() {
            return growth;
        }

        public void setGrowth(double growth) {
            this.growth = growth;
        }

        public int getMemberCount() {
            return memberCount;
        }

        public void setMemberCount(int memberCount) {
            this.memberCount = memberCount;
        }

        public double getInfluence() {
            return influence;
        }

        public void setInfluence(double influence) {
            this.influence = influence;
        }
    }

    public static class InstitutionProfile {
        private String institutionId;
        private String branch;
        private String level;
        private String jurisdictionId;
        private String displayName;
        private double libertyLean;
        private double orderLean;
        private double welfareLean;
        private double growthLean;
        private double legitimacy;
        private int workload;
        private int capacityPerTick;
        private String proceduralRule;

        public String getInstitutionId() {
            return institutionId;
        }

        public void setInstitutionId(String institutionId) {
            this.institutionId = institutionId;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public double getLibertyLean() {
            return libertyLean;
        }

        public void setLibertyLean(double libertyLean) {
            this.libertyLean = libertyLean;
        }

        public double getOrderLean() {
            return orderLean;
        }

        public void setOrderLean(double orderLean) {
            this.orderLean = orderLean;
        }

        public double getWelfareLean() {
            return welfareLean;
        }

        public void setWelfareLean(double welfareLean) {
            this.welfareLean = welfareLean;
        }

        public double getGrowthLean() {
            return growthLean;
        }

        public void setGrowthLean(double growthLean) {
            this.growthLean = growthLean;
        }

        public double getLegitimacy() {
            return legitimacy;
        }

        public void setLegitimacy(double legitimacy) {
            this.legitimacy = legitimacy;
        }

        public int getWorkload() {
            return workload;
        }

        public void setWorkload(int workload) {
            this.workload = workload;
        }

        public int getCapacityPerTick() {
            return capacityPerTick;
        }

        public void setCapacityPerTick(int capacityPerTick) {
            this.capacityPerTick = capacityPerTick;
        }

        public String getProceduralRule() {
            return proceduralRule;
        }

        public void setProceduralRule(String proceduralRule) {
            this.proceduralRule = proceduralRule;
        }
    }

    public static class GovernmentBill {
        private String billId;
        private String title;
        private String scope;
        private String stateId;
        private String sponsorPartyId;
        private String policyDomain;
        private String stage;
        private String lawStatus;
        private String constitutionalBasis;
        private String summary;
        private Instant createdAt;
        private Instant updatedAt;
        private int lowerVotes;
        private int upperVotes;
        private boolean vetoed;
        private List<String> sections = new ArrayList<>();
        private List<String> affectedRights = new ArrayList<>();
        private List<String> enforcementMechanisms = new ArrayList<>();
        private List<String> implementationNotes = new ArrayList<>();
        private String preemptionScope;
        private Instant effectiveAt;
        private String committeeName;
        private String sponsorActorId;
        private List<String> amendmentSummaries = new ArrayList<>();
        private List<String> coalitionPartyIds = new ArrayList<>();
        private int whipCount;
        private boolean conferenceRequired;
        private String chamberTextStatus;
        private String implementationStatus;
        private java.util.Map<String, Long> fundingChanges = new java.util.LinkedHashMap<>();
        private List<String> worldEffects = new ArrayList<>();

        public String getBillId() {
            return billId;
        }

        public void setBillId(String billId) {
            this.billId = billId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getStateId() {
            return stateId;
        }

        public void setStateId(String stateId) {
            this.stateId = stateId;
        }

        public String getSponsorPartyId() {
            return sponsorPartyId;
        }

        public void setSponsorPartyId(String sponsorPartyId) {
            this.sponsorPartyId = sponsorPartyId;
        }

        public String getPolicyDomain() {
            return policyDomain;
        }

        public void setPolicyDomain(String policyDomain) {
            this.policyDomain = policyDomain;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public String getLawStatus() {
            return lawStatus;
        }

        public void setLawStatus(String lawStatus) {
            this.lawStatus = lawStatus;
        }

        public String getConstitutionalBasis() {
            return constitutionalBasis;
        }

        public void setConstitutionalBasis(String constitutionalBasis) {
            this.constitutionalBasis = constitutionalBasis;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public int getLowerVotes() {
            return lowerVotes;
        }

        public void setLowerVotes(int lowerVotes) {
            this.lowerVotes = lowerVotes;
        }

        public int getUpperVotes() {
            return upperVotes;
        }

        public void setUpperVotes(int upperVotes) {
            this.upperVotes = upperVotes;
        }

        public boolean isVetoed() {
            return vetoed;
        }

        public void setVetoed(boolean vetoed) {
            this.vetoed = vetoed;
        }

        public List<String> getSections() {
            return sections;
        }

        public void setSections(List<String> sections) {
            this.sections = sections;
        }

        public List<String> getAffectedRights() {
            return affectedRights;
        }

        public void setAffectedRights(List<String> affectedRights) {
            this.affectedRights = affectedRights;
        }

        public List<String> getEnforcementMechanisms() {
            return enforcementMechanisms;
        }

        public void setEnforcementMechanisms(List<String> enforcementMechanisms) {
            this.enforcementMechanisms = enforcementMechanisms;
        }

        public List<String> getImplementationNotes() {
            return implementationNotes;
        }

        public void setImplementationNotes(List<String> implementationNotes) {
            this.implementationNotes = implementationNotes;
        }

        public String getPreemptionScope() {
            return preemptionScope;
        }

        public void setPreemptionScope(String preemptionScope) {
            this.preemptionScope = preemptionScope;
        }

        public Instant getEffectiveAt() {
            return effectiveAt;
        }

        public void setEffectiveAt(Instant effectiveAt) {
            this.effectiveAt = effectiveAt;
        }

        public String getCommitteeName() {
            return committeeName;
        }

        public void setCommitteeName(String committeeName) {
            this.committeeName = committeeName;
        }

        public String getSponsorActorId() {
            return sponsorActorId;
        }

        public void setSponsorActorId(String sponsorActorId) {
            this.sponsorActorId = sponsorActorId;
        }

        public List<String> getAmendmentSummaries() {
            return amendmentSummaries;
        }

        public void setAmendmentSummaries(List<String> amendmentSummaries) {
            this.amendmentSummaries = amendmentSummaries;
        }

        public List<String> getCoalitionPartyIds() {
            return coalitionPartyIds;
        }

        public void setCoalitionPartyIds(List<String> coalitionPartyIds) {
            this.coalitionPartyIds = coalitionPartyIds;
        }

        public int getWhipCount() {
            return whipCount;
        }

        public void setWhipCount(int whipCount) {
            this.whipCount = whipCount;
        }

        public boolean isConferenceRequired() {
            return conferenceRequired;
        }

        public void setConferenceRequired(boolean conferenceRequired) {
            this.conferenceRequired = conferenceRequired;
        }

        public String getChamberTextStatus() {
            return chamberTextStatus;
        }

        public void setChamberTextStatus(String chamberTextStatus) {
            this.chamberTextStatus = chamberTextStatus;
        }

        public String getImplementationStatus() {
            return implementationStatus;
        }

        public void setImplementationStatus(String implementationStatus) {
            this.implementationStatus = implementationStatus;
        }

        public java.util.Map<String, Long> getFundingChanges() {
            return fundingChanges;
        }

        public void setFundingChanges(java.util.Map<String, Long> fundingChanges) {
            this.fundingChanges = fundingChanges;
        }

        public List<String> getWorldEffects() {
            return worldEffects;
        }

        public void setWorldEffects(List<String> worldEffects) {
            this.worldEffects = worldEffects;
        }
    }

    public static class ElectionRecord {
        private String electionId;
        private String scope;
        private String jurisdictionId;
        private Instant heldAt;
        private String winningPartyId;
        private double turnout;
        private String summary;

        public String getElectionId() {
            return electionId;
        }

        public void setElectionId(String electionId) {
            this.electionId = electionId;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public Instant getHeldAt() {
            return heldAt;
        }

        public void setHeldAt(Instant heldAt) {
            this.heldAt = heldAt;
        }

        public String getWinningPartyId() {
            return winningPartyId;
        }

        public void setWinningPartyId(String winningPartyId) {
            this.winningPartyId = winningPartyId;
        }

        public double getTurnout() {
            return turnout;
        }

        public void setTurnout(double turnout) {
            this.turnout = turnout;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }
    }

    public static class ExecutiveOrder {
        private String orderId;
        private String scope;
        private String jurisdictionId;
        private String issuedByPartyId;
        private String policyDomain;
        private String summary;
        private String status;
        private Instant issuedAt;
        private Instant effectiveAt;
        private String preemptionScope;
        private String agencyName;
        private String emergencyBasis;
        private boolean challengedImmediately;
        private List<String> directives = new ArrayList<>();
        private List<String> affectedRights = new ArrayList<>();
        private List<String> enforcementMechanisms = new ArrayList<>();
        private List<String> implementationNotes = new ArrayList<>();
        private java.util.Map<String, Long> fundingChanges = new java.util.LinkedHashMap<>();
        private List<String> affectedSystems = new ArrayList<>();

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public String getIssuedByPartyId() {
            return issuedByPartyId;
        }

        public void setIssuedByPartyId(String issuedByPartyId) {
            this.issuedByPartyId = issuedByPartyId;
        }

        public String getPolicyDomain() {
            return policyDomain;
        }

        public void setPolicyDomain(String policyDomain) {
            this.policyDomain = policyDomain;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getIssuedAt() {
            return issuedAt;
        }

        public void setIssuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
        }

        public Instant getEffectiveAt() {
            return effectiveAt;
        }

        public void setEffectiveAt(Instant effectiveAt) {
            this.effectiveAt = effectiveAt;
        }

        public String getPreemptionScope() {
            return preemptionScope;
        }

        public void setPreemptionScope(String preemptionScope) {
            this.preemptionScope = preemptionScope;
        }

        public String getAgencyName() {
            return agencyName;
        }

        public void setAgencyName(String agencyName) {
            this.agencyName = agencyName;
        }

        public String getEmergencyBasis() {
            return emergencyBasis;
        }

        public void setEmergencyBasis(String emergencyBasis) {
            this.emergencyBasis = emergencyBasis;
        }

        public boolean isChallengedImmediately() {
            return challengedImmediately;
        }

        public void setChallengedImmediately(boolean challengedImmediately) {
            this.challengedImmediately = challengedImmediately;
        }

        public List<String> getDirectives() {
            return directives;
        }

        public void setDirectives(List<String> directives) {
            this.directives = directives;
        }

        public List<String> getAffectedRights() {
            return affectedRights;
        }

        public void setAffectedRights(List<String> affectedRights) {
            this.affectedRights = affectedRights;
        }

        public List<String> getEnforcementMechanisms() {
            return enforcementMechanisms;
        }

        public void setEnforcementMechanisms(List<String> enforcementMechanisms) {
            this.enforcementMechanisms = enforcementMechanisms;
        }

        public List<String> getImplementationNotes() {
            return implementationNotes;
        }

        public void setImplementationNotes(List<String> implementationNotes) {
            this.implementationNotes = implementationNotes;
        }

        public java.util.Map<String, Long> getFundingChanges() {
            return fundingChanges;
        }

        public void setFundingChanges(java.util.Map<String, Long> fundingChanges) {
            this.fundingChanges = fundingChanges;
        }

        public List<String> getAffectedSystems() {
            return affectedSystems;
        }

        public void setAffectedSystems(List<String> affectedSystems) {
            this.affectedSystems = affectedSystems;
        }
    }

    public static class CourtCase {
        private String caseId;
        private String courtLevel;
        private String plaintiff;
        private String defendant;
        private String challengedTitle;
        private String issueSummary;
        private String reviewPosture;
        private String status;
        private String stage;
        private String scrutinyLevel;
        private String standingBasis;
        private String remedy;
        private String doctrine;
        private String sourcePolicyDomain;
        private String legalBasis;
        private String decision;
        private String constitutionalBasis;
        private String lowerCourtDisposition;
        private String appellatePath;
        private boolean injunctionGranted;
        private String dissentSummary;
        private boolean remanded;
        private String majorityAlignment;
        private Instant filedAt;
        private Instant briefedAt;
        private Instant arguedAt;
        private Instant conferenceAt;
        private Instant opinionDraftAt;
        private Instant decidedAt;
        private boolean blockedAction;
        private List<String> affectedLaws = new ArrayList<>();
        private List<String> precedentIds = new ArrayList<>();

        public String getCaseId() {
            return caseId;
        }

        public void setCaseId(String caseId) {
            this.caseId = caseId;
        }

        public String getCourtLevel() {
            return courtLevel;
        }

        public void setCourtLevel(String courtLevel) {
            this.courtLevel = courtLevel;
        }

        public String getPlaintiff() {
            return plaintiff;
        }

        public void setPlaintiff(String plaintiff) {
            this.plaintiff = plaintiff;
        }

        public String getDefendant() {
            return defendant;
        }

        public void setDefendant(String defendant) {
            this.defendant = defendant;
        }

        public String getChallengedTitle() {
            return challengedTitle;
        }

        public void setChallengedTitle(String challengedTitle) {
            this.challengedTitle = challengedTitle;
        }

        public String getIssueSummary() {
            return issueSummary;
        }

        public void setIssueSummary(String issueSummary) {
            this.issueSummary = issueSummary;
        }

        public String getReviewPosture() {
            return reviewPosture;
        }

        public void setReviewPosture(String reviewPosture) {
            this.reviewPosture = reviewPosture;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public String getScrutinyLevel() {
            return scrutinyLevel;
        }

        public void setScrutinyLevel(String scrutinyLevel) {
            this.scrutinyLevel = scrutinyLevel;
        }

        public String getStandingBasis() {
            return standingBasis;
        }

        public void setStandingBasis(String standingBasis) {
            this.standingBasis = standingBasis;
        }

        public String getRemedy() {
            return remedy;
        }

        public void setRemedy(String remedy) {
            this.remedy = remedy;
        }

        public String getDoctrine() {
            return doctrine;
        }

        public void setDoctrine(String doctrine) {
            this.doctrine = doctrine;
        }

        public String getSourcePolicyDomain() {
            return sourcePolicyDomain;
        }

        public void setSourcePolicyDomain(String sourcePolicyDomain) {
            this.sourcePolicyDomain = sourcePolicyDomain;
        }

        public String getLegalBasis() {
            return legalBasis;
        }

        public void setLegalBasis(String legalBasis) {
            this.legalBasis = legalBasis;
        }

        public String getDecision() {
            return decision;
        }

        public void setDecision(String decision) {
            this.decision = decision;
        }

        public String getConstitutionalBasis() {
            return constitutionalBasis;
        }

        public void setConstitutionalBasis(String constitutionalBasis) {
            this.constitutionalBasis = constitutionalBasis;
        }

        public String getLowerCourtDisposition() {
            return lowerCourtDisposition;
        }

        public void setLowerCourtDisposition(String lowerCourtDisposition) {
            this.lowerCourtDisposition = lowerCourtDisposition;
        }

        public String getAppellatePath() {
            return appellatePath;
        }

        public void setAppellatePath(String appellatePath) {
            this.appellatePath = appellatePath;
        }

        public boolean isInjunctionGranted() {
            return injunctionGranted;
        }

        public void setInjunctionGranted(boolean injunctionGranted) {
            this.injunctionGranted = injunctionGranted;
        }

        public String getDissentSummary() {
            return dissentSummary;
        }

        public void setDissentSummary(String dissentSummary) {
            this.dissentSummary = dissentSummary;
        }

        public boolean isRemanded() {
            return remanded;
        }

        public void setRemanded(boolean remanded) {
            this.remanded = remanded;
        }

        public String getMajorityAlignment() {
            return majorityAlignment;
        }

        public void setMajorityAlignment(String majorityAlignment) {
            this.majorityAlignment = majorityAlignment;
        }

        public Instant getDecidedAt() {
            return decidedAt;
        }

        public void setDecidedAt(Instant decidedAt) {
            this.decidedAt = decidedAt;
        }

        public Instant getFiledAt() {
            return filedAt;
        }

        public void setFiledAt(Instant filedAt) {
            this.filedAt = filedAt;
        }

        public Instant getBriefedAt() {
            return briefedAt;
        }

        public void setBriefedAt(Instant briefedAt) {
            this.briefedAt = briefedAt;
        }

        public Instant getArguedAt() {
            return arguedAt;
        }

        public void setArguedAt(Instant arguedAt) {
            this.arguedAt = arguedAt;
        }

        public Instant getConferenceAt() {
            return conferenceAt;
        }

        public void setConferenceAt(Instant conferenceAt) {
            this.conferenceAt = conferenceAt;
        }

        public Instant getOpinionDraftAt() {
            return opinionDraftAt;
        }

        public void setOpinionDraftAt(Instant opinionDraftAt) {
            this.opinionDraftAt = opinionDraftAt;
        }

        public boolean isBlockedAction() {
            return blockedAction;
        }

        public void setBlockedAction(boolean blockedAction) {
            this.blockedAction = blockedAction;
        }

        public List<String> getAffectedLaws() {
            return affectedLaws;
        }

        public void setAffectedLaws(List<String> affectedLaws) {
            this.affectedLaws = affectedLaws;
        }

        public List<String> getPrecedentIds() {
            return precedentIds;
        }

        public void setPrecedentIds(List<String> precedentIds) {
            this.precedentIds = precedentIds;
        }
    }

    public static class LegalPrecedent {
        private String precedentId;
        private String caseId;
        private String doctrine;
        private String scope;
        private String policyDomain;
        private String holding;
        private String outcome;
        private String constitutionalBasis;
        private Instant createdAt;
        private String summary;
        private double weight;

        public String getPrecedentId() {
            return precedentId;
        }

        public void setPrecedentId(String precedentId) {
            this.precedentId = precedentId;
        }

        public String getCaseId() {
            return caseId;
        }

        public void setCaseId(String caseId) {
            this.caseId = caseId;
        }

        public String getDoctrine() {
            return doctrine;
        }

        public void setDoctrine(String doctrine) {
            this.doctrine = doctrine;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getPolicyDomain() {
            return policyDomain;
        }

        public void setPolicyDomain(String policyDomain) {
            this.policyDomain = policyDomain;
        }

        public String getHolding() {
            return holding;
        }

        public void setHolding(String holding) {
            this.holding = holding;
        }

        public String getOutcome() {
            return outcome;
        }

        public void setOutcome(String outcome) {
            this.outcome = outcome;
        }

        public String getConstitutionalBasis() {
            return constitutionalBasis;
        }

        public void setConstitutionalBasis(String constitutionalBasis) {
            this.constitutionalBasis = constitutionalBasis;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }

    public static class CivicActor {
        private String actorId;
        private String name;
        private String actorType;
        private String scope;
        private String jurisdictionId;
        private String alignedPartyId;
        private double influence;
        private List<String> agendas = new ArrayList<>();

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getActorType() {
            return actorType;
        }

        public void setActorType(String actorType) {
            this.actorType = actorType;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public String getAlignedPartyId() {
            return alignedPartyId;
        }

        public void setAlignedPartyId(String alignedPartyId) {
            this.alignedPartyId = alignedPartyId;
        }

        public double getInfluence() {
            return influence;
        }

        public void setInfluence(double influence) {
            this.influence = influence;
        }

        public List<String> getAgendas() {
            return agendas;
        }

        public void setAgendas(List<String> agendas) {
            this.agendas = agendas;
        }
    }

    public static class AdministrativeAction {
        private String actionId;
        private String agencyName;
        private String actionType;
        private String scope;
        private String jurisdictionId;
        private String status;
        private String sourceBillId;
        private String sourceOrderId;
        private String title;
        private String summary;
        private String stage;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean appealed;
        private boolean stayed;

        public String getActionId() {
            return actionId;
        }

        public void setActionId(String actionId) {
            this.actionId = actionId;
        }

        public String getAgencyName() {
            return agencyName;
        }

        public void setAgencyName(String agencyName) {
            this.agencyName = agencyName;
        }

        public String getActionType() {
            return actionType;
        }

        public void setActionType(String actionType) {
            this.actionType = actionType;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getSourceBillId() {
            return sourceBillId;
        }

        public void setSourceBillId(String sourceBillId) {
            this.sourceBillId = sourceBillId;
        }

        public String getSourceOrderId() {
            return sourceOrderId;
        }

        public void setSourceOrderId(String sourceOrderId) {
            this.sourceOrderId = sourceOrderId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public boolean isAppealed() {
            return appealed;
        }

        public void setAppealed(boolean appealed) {
            this.appealed = appealed;
        }

        public boolean isStayed() {
            return stayed;
        }

        public void setStayed(boolean stayed) {
            this.stayed = stayed;
        }
    }

    public static class PoliticalPressure {
        private String eventId;
        private String pressureType;
        private String sourceActorId;
        private String targetInstitutionId;
        private String summary;
        private String effect;
        private double intensity;
        private Instant createdAt;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getPressureType() {
            return pressureType;
        }

        public void setPressureType(String pressureType) {
            this.pressureType = pressureType;
        }

        public String getSourceActorId() {
            return sourceActorId;
        }

        public void setSourceActorId(String sourceActorId) {
            this.sourceActorId = sourceActorId;
        }

        public String getTargetInstitutionId() {
            return targetInstitutionId;
        }

        public void setTargetInstitutionId(String targetInstitutionId) {
            this.targetInstitutionId = targetInstitutionId;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getEffect() {
            return effect;
        }

        public void setEffect(String effect) {
            this.effect = effect;
        }

        public double getIntensity() {
            return intensity;
        }

        public void setIntensity(double intensity) {
            this.intensity = intensity;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static class FederalStateConflict {
        private String conflictId;
        private String stateId;
        private String federalPartyId;
        private String statePartyId;
        private String conflictType;
        private String status;
        private String relatedBillId;
        private String relatedOrderId;
        private String summary;
        private Instant createdAt;
        private Instant resolvedAt;

        public String getConflictId() {
            return conflictId;
        }

        public void setConflictId(String conflictId) {
            this.conflictId = conflictId;
        }

        public String getStateId() {
            return stateId;
        }

        public void setStateId(String stateId) {
            this.stateId = stateId;
        }

        public String getFederalPartyId() {
            return federalPartyId;
        }

        public void setFederalPartyId(String federalPartyId) {
            this.federalPartyId = federalPartyId;
        }

        public String getStatePartyId() {
            return statePartyId;
        }

        public void setStatePartyId(String statePartyId) {
            this.statePartyId = statePartyId;
        }

        public String getConflictType() {
            return conflictType;
        }

        public void setConflictType(String conflictType) {
            this.conflictType = conflictType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getRelatedBillId() {
            return relatedBillId;
        }

        public void setRelatedBillId(String relatedBillId) {
            this.relatedBillId = relatedBillId;
        }

        public String getRelatedOrderId() {
            return relatedOrderId;
        }

        public void setRelatedOrderId(String relatedOrderId) {
            this.relatedOrderId = relatedOrderId;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getResolvedAt() {
            return resolvedAt;
        }

        public void setResolvedAt(Instant resolvedAt) {
            this.resolvedAt = resolvedAt;
        }
    }

    public static class ImplementationOutcome {
        private String outcomeId;
        private String sourceType;
        private String sourceId;
        private String title;
        private String jurisdictionId;
        private String status;
        private double capacityScore;
        private double outcomeScore;
        private String summary;
        private Instant measuredAt;
        private List<String> adverseEffects = new ArrayList<>();

        public String getOutcomeId() {
            return outcomeId;
        }

        public void setOutcomeId(String outcomeId) {
            this.outcomeId = outcomeId;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getJurisdictionId() {
            return jurisdictionId;
        }

        public void setJurisdictionId(String jurisdictionId) {
            this.jurisdictionId = jurisdictionId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getCapacityScore() {
            return capacityScore;
        }

        public void setCapacityScore(double capacityScore) {
            this.capacityScore = capacityScore;
        }

        public double getOutcomeScore() {
            return outcomeScore;
        }

        public void setOutcomeScore(double outcomeScore) {
            this.outcomeScore = outcomeScore;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public Instant getMeasuredAt() {
            return measuredAt;
        }

        public void setMeasuredAt(Instant measuredAt) {
            this.measuredAt = measuredAt;
        }

        public List<String> getAdverseEffects() {
            return adverseEffects;
        }

        public void setAdverseEffects(List<String> adverseEffects) {
            this.adverseEffects = adverseEffects;
        }
    }

    public static class HistoricalMarker {
        private String markerId;
        private String markerType;
        private String title;
        private String summary;
        private long tick;
        private String doctrine;
        private String partyAlignment;
        private Instant createdAt;

        public String getMarkerId() {
            return markerId;
        }

        public void setMarkerId(String markerId) {
            this.markerId = markerId;
        }

        public String getMarkerType() {
            return markerType;
        }

        public void setMarkerType(String markerType) {
            this.markerType = markerType;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public long getTick() {
            return tick;
        }

        public void setTick(long tick) {
            this.tick = tick;
        }

        public String getDoctrine() {
            return doctrine;
        }

        public void setDoctrine(String doctrine) {
            this.doctrine = doctrine;
        }

        public String getPartyAlignment() {
            return partyAlignment;
        }

        public void setPartyAlignment(String partyAlignment) {
            this.partyAlignment = partyAlignment;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }
}
