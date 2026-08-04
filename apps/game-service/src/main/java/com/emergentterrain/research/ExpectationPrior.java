package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2 Iter 1 — Welford-style running prior over a bot's published peer
 * scores in a single domain. Surprise scoring uses this as the bot's "what
 * I expect to see" baseline; deviations beyond two standard deviations get
 * flagged as {@link SurpriseEvent} rows.
 *
 * Updated by {@link ExpectationModelService#updatePrior} on every published
 * paper. The first {@code BOOTSTRAP_SAMPLE_COUNT} papers seed the prior;
 * surprise scoring is suppressed until the prior has enough samples for
 * the variance estimate to be meaningful.
 *
 * One row per (botId, domain) pair. Composite uniqueness is enforced via a
 * unique index because Hibernate doesn't natively support composite keys
 * on top of a generated UUID id without a separate @IdClass.
 */
@Entity
@Table(
        name = "expectation_priors",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_priors_bot_domain", columnNames = {"bot_id", "domain"}),
        indexes = @Index(name = "idx_priors_bot", columnList = "bot_id")
)
public class ExpectationPrior {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(nullable = false, length = 64)
    private String domain;

    /** Welford running mean of observed primary values. */
    @Column(name = "expected_mean", nullable = false)
    private double expectedMean = 0.0;

    /** Welford M2 (sum of squared deltas from running mean). variance = M2 / (n-1). */
    @Column(nullable = false)
    private double m2 = 0.0;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    protected ExpectationPrior() {}

    public ExpectationPrior(UUID botId, String domain, Instant lastUpdatedAt) {
        this.botId         = botId;
        this.domain        = domain;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /**
     * Variance estimate, undefined when fewer than 2 samples — callers should
     * gate surprise scoring on {@link #sampleCount} before using this.
     */
    public double variance() {
        return sampleCount > 1 ? m2 / (sampleCount - 1) : 0.0;
    }

    /** Standard deviation; 0 when variance is undefined. */
    public double stdDev() {
        return Math.sqrt(variance());
    }

    public UUID    getId()             { return id; }
    public UUID    getBotId()          { return botId; }
    public String  getDomain()         { return domain; }
    public double  getExpectedMean()   { return expectedMean; }
    public double  getM2()             { return m2; }
    public int     getSampleCount()    { return sampleCount; }
    public Instant getLastUpdatedAt()  { return lastUpdatedAt; }

    public void setExpectedMean(double m)   { this.expectedMean = m; }
    public void setM2(double v)             { this.m2 = v; }
    public void setSampleCount(int c)       { this.sampleCount = c; }
    public void setLastUpdatedAt(Instant t) { this.lastUpdatedAt = t; }
}
