package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profiles")
public class Profile {
    @Id
    private UUID id;

    @ManyToOne(optional = false)
    private User user;

    private int age;

    @Column(nullable = false)
    private String sex;

    @Column(nullable = false)
    private String location;

    @Column(nullable = false, columnDefinition = "text")
    private String answersJson;

    @Column(nullable = false, columnDefinition = "text")
    private String hiddenPropsJson;

    @Column(nullable = false)
    private String payloadHash;

    private UUID lastRoomId;

    public void setId(UUID id) {
        this.id = id;
    }

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

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

    public String getAnswersJson() {
        return answersJson;
    }

    public void setAnswersJson(String answersJson) {
        this.answersJson = answersJson;
    }

    public String getHiddenPropsJson() {
        return hiddenPropsJson;
    }

    public void setHiddenPropsJson(String hiddenPropsJson) {
        this.hiddenPropsJson = hiddenPropsJson;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public UUID getLastRoomId() {
        return lastRoomId;
    }

    public void setLastRoomId(UUID lastRoomId) {
        this.lastRoomId = lastRoomId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
