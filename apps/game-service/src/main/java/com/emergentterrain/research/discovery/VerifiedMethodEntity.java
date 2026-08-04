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
 * Phase 30 fork 2b — a persisted {@link VerifiedMethod} (the reusable verified-
 * method library). Unique per {@code (bound, method_sha256)}; updated to the
 * furthest reach / cheapest cost seen. Created fresh by ddl-auto.
 */
@Entity
@Table(name = "verified_methods",
        uniqueConstraints = @UniqueConstraint(name = "uq_method_bound_sha", columnNames = {"bound", "method_sha256"}),
        indexes = @Index(name = "ix_method_bound", columnList = "bound"))
public class VerifiedMethodEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String bound;

    @Column(name = "method_sha256", nullable = false)
    private String methodSha256;

    @Column(columnDefinition = "text")
    private String code;

    @Column(name = "max_scale", nullable = false)
    private long maxScale;

    @Column(name = "cost_ms", nullable = false)
    private long costMs;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected VerifiedMethodEntity() {}

    public void apply(VerifiedMethod m) {
        this.bound = m.bound();
        this.methodSha256 = m.methodSha256();
        this.code = m.code();
        this.maxScale = m.maxScale();
        this.costMs = m.costMs();
        this.updatedAt = Instant.now();
    }

    public VerifiedMethod toDto() {
        return new VerifiedMethod(bound, methodSha256, code, maxScale, costMs);
    }
}
