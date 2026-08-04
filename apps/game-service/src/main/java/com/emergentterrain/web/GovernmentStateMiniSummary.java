package com.emergentterrain.web;

public class GovernmentStateMiniSummary {
    private String name;
    private String districtingMode;
    private int districtCount;
    private String executiveParty;
    private String lowerParty;
    private String upperParty;
    private String chiefJusticeParty;
    private double trust;
    private double serviceAccess;
    private long revenue;
    private long infrastructure;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getExecutiveParty() {
        return executiveParty;
    }

    public void setExecutiveParty(String executiveParty) {
        this.executiveParty = executiveParty;
    }

    public String getLowerParty() {
        return lowerParty;
    }

    public void setLowerParty(String lowerParty) {
        this.lowerParty = lowerParty;
    }

    public String getUpperParty() {
        return upperParty;
    }

    public void setUpperParty(String upperParty) {
        this.upperParty = upperParty;
    }

    public String getChiefJusticeParty() {
        return chiefJusticeParty;
    }

    public void setChiefJusticeParty(String chiefJusticeParty) {
        this.chiefJusticeParty = chiefJusticeParty;
    }

    public double getTrust() {
        return trust;
    }

    public void setTrust(double trust) {
        this.trust = trust;
    }

    public double getServiceAccess() {
        return serviceAccess;
    }

    public void setServiceAccess(double serviceAccess) {
        this.serviceAccess = serviceAccess;
    }

    public long getRevenue() {
        return revenue;
    }

    public void setRevenue(long revenue) {
        this.revenue = revenue;
    }

    public long getInfrastructure() {
        return infrastructure;
    }

    public void setInfrastructure(long infrastructure) {
        this.infrastructure = infrastructure;
    }
}
