package com.emergentterrain.web;

public class ProfileForm {
    private int age;
    private String sex;
    private String location;

    private boolean likesForests;
    private boolean likesWater;
    private boolean likesMountains;
    private boolean likesOpenFields;
    private boolean likesWetClimate;
    private boolean likesSunny;

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isLikesForests() {
        return likesForests;
    }

    public void setLikesForests(boolean likesForests) {
        this.likesForests = likesForests;
    }

    public boolean isLikesWater() {
        return likesWater;
    }

    public void setLikesWater(boolean likesWater) {
        this.likesWater = likesWater;
    }

    public boolean isLikesMountains() {
        return likesMountains;
    }

    public void setLikesMountains(boolean likesMountains) {
        this.likesMountains = likesMountains;
    }

    public boolean isLikesOpenFields() {
        return likesOpenFields;
    }

    public void setLikesOpenFields(boolean likesOpenFields) {
        this.likesOpenFields = likesOpenFields;
    }

    public boolean isLikesWetClimate() {
        return likesWetClimate;
    }

    public void setLikesWetClimate(boolean likesWetClimate) {
        this.likesWetClimate = likesWetClimate;
    }

    public boolean isLikesSunny() {
        return likesSunny;
    }

    public void setLikesSunny(boolean likesSunny) {
        this.likesSunny = likesSunny;
    }
}
