package com.emergentterrain.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "government_timeline_event",
    indexes = {
        @Index(name = "idx_gov_timeline_event_room_time", columnList = "room_id, occurredAt"),
        @Index(name = "idx_gov_timeline_event_room_kind_ref", columnList = "room_id, kind, referenceId")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_gov_timeline_event_room_kind_ref_phase", columnNames = {"room_id", "kind", "referenceId", "phase"})
    }
)
public class GovernmentTimelineEvent {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Room room;

    @Column(nullable = false)
    private long tick;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, length = 128)
    private String referenceId;

    @Column(nullable = false, length = 128)
    private String phase;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(length = 64)
    private String scope;

    @Column(columnDefinition = "text")
    private String impact;

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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getImpact() {
        return impact;
    }

    public void setImpact(String impact) {
        this.impact = impact;
    }
}
