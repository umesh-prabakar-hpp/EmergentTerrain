package com.emergentterrain.research.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 30 fork 2b §10.3 — a persisted {@link FrontierEvent} (a discovery failure /
 * computational boundary). Unique per {@code (bound, scale_x, outcome)} so a repeated
 * same-kind failure at the same x upserts rather than piling up. Created fresh by
 * ddl-auto=update.
 */
@Entity
@Table(name = "frontier_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_event_bound_x_outcome",
                columnNames = {"bound", "scale_x", "outcome"}),
        indexes = @Index(name = "ix_event_bound", columnList = "bound"))
public class FrontierEventEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String bound;

    @Column(name = "scale_x", nullable = false)
    private long scaleX;

    @Column(nullable = false)
    private String outcome;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String code;

    @Column(name = "code_sha256")
    private String codeSha256;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected FrontierEventEntity() {}

    public void apply(FrontierEvent e) {
        this.bound = e.bound();
        this.scaleX = e.x();
        this.outcome = e.outcome();
        this.reason = e.reason();
        this.code = e.code();
        this.codeSha256 = e.codeSha256();
        this.updatedAt = Instant.now();
    }

    public FrontierEvent toDto() {
        return new FrontierEvent(bound, scaleX, outcome, reason, code, codeSha256);
    }
}
