package com.emergentterrain.web;

public class GovernmentBudgetSummary {
    private long revenue;
    private long education;
    private long health;
    private long infrastructure;
    private long welfare;
    private long justice;
    private long payroll;
    private double serviceAccess;

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

    public double getServiceAccess() {
        return serviceAccess;
    }

    public void setServiceAccess(double serviceAccess) {
        this.serviceAccess = serviceAccess;
    }
}
