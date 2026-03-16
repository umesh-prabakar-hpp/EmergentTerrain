package com.emergentterrain.government;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GovernmentState {
    private String constitutionVersion;
    private long tick;
    private Instant lastTickAt;
    private Instant nextElectionAt;
    private Instant lastBillAt;
    private int nextBillSequence;
    private int nextElectionSequence;
    private int nextEventSequence;
    private List<ConstitutionRule> constitutionalRules = new ArrayList<>();
    private GovernmentNode federal = new GovernmentNode();
    private List<GovernmentNode> states = new ArrayList<>();
    private List<PoliticalParty> parties = new ArrayList<>();
    private List<GovernmentBill> bills = new ArrayList<>();
    private List<ElectionRecord> elections = new ArrayList<>();

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
}
