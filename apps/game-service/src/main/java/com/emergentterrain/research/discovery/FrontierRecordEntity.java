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
 * Phase 30 fork 2b — a persisted {@link FrontierRecord} (one accumulated validated
 * frontier value). Unique per {@code (bound, scale_x)} so keep-the-best upserts.
 * Created fresh by ddl-auto=update on first boot.
 */
@Entity
@Table(name = "frontier_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_frontier_bound_scale", columnNames = {"bound", "scale_x"}),
        indexes = @Index(name = "ix_frontier_bound", columnList = "bound"))
public class FrontierRecordEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String bound;

    @Column(name = "scale_x", nullable = false)
    private long scaleX;

    @Column(name = "result_value", nullable = false)
    private long resultValue;

    private String source;

    @Column(nullable = false)
    private String trust;   // TrustLevel.name()

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected FrontierRecordEntity() {}

    public void apply(FrontierRecord r) {
        this.bound = r.bound();
        this.scaleX = r.x();
        this.resultValue = r.value();
        this.source = r.source();
        this.trust = r.trust().name();
    }

    public FrontierRecord toDto() {
        return new FrontierRecord(bound, scaleX, resultValue, source, TrustLevel.valueOf(trust));
    }
}
