package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent conjecture row. DB-backed analog of the engine-side
 * {@link Inequality}: same fields, plus a {@code domain} tag and
 * audit timestamps. Collection fields (confirmedConfigs, slackByConfig,
 * refutedBy, oeisMatch) are stored as JSON strings in TEXT columns;
 * the engine doesn't query into them, so the JSON-blob pattern keeps
 * the schema flat and avoids a sidecar element-collection table per
 * field.
 *
 * <p>Conversion between {@code Conjecture} and {@link Inequality}
 * lives in {@link ConjectureMapper} (uses Jackson). The engine never
 * touches this class directly.
 *
 * <p>Hibernate ddl-auto=update creates {@code conjectures} on first
 * boot; no migration script needed.
 */
@Entity
@Table(name = "conjectures", indexes = {
        @Index(name = "ix_conjectures_domain", columnList = "domain"),
        @Index(name = "ix_conjectures_domain_lhs", columnList = "domain,lhs"),
})
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class Conjecture {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String lhs;

    @Column(nullable = false)
    private String rhs;

    @Column(nullable = false)
    private double k;

    @Column(nullable = false)
    private double c;

    @Column(nullable = false)
    private int confirmedRuns;

    @Column(nullable = false)
    private double p99Slack;

    private String droppedByKnob;

    /** JSON-encoded {@code List<String>} of config_hashes. */
    @Column(columnDefinition = "text")
    private String confirmedConfigsJson;

    /** JSON-encoded {@code Map<String,Double>} of config_hash → worst slack. */
    @Column(columnDefinition = "text")
    private String slackByConfigJson;

    /** JSON-encoded {@code List<Witness>}. */
    @Column(columnDefinition = "text")
    private String refutedByJson;

    /**
     * JSON-encoded OEIS annotation (Phase 23.2). Shape:
     * <pre>{"lhs": [{"id":"A005101","name":"Abundant numbers"}, ...],
     *      "rhs": [...], "has_match": true}</pre>
     * Null until the 23.2 OEIS-annotation pass tags it.
     */
    @Column(columnDefinition = "text")
    private String oeisMatchJson;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    /** Set when {@code confirmedRuns} first crosses the promotion threshold. */
    private Instant promotedAt;

    protected Conjecture() {}  // for JPA

    public Conjecture(String domain, String lhs, String rhs, double k, double c) {
        this.domain = domain;
        this.lhs = lhs;
        this.rhs = rhs;
        this.k = k;
        this.c = c;
        this.createdAt = Instant.now();
    }

    // Record-style accessors, matching the convention in ResearchPaper.
    public UUID id()                       { return id; }
    public String domain()                 { return domain; }
    public String lhs()                    { return lhs; }
    public String rhs()                    { return rhs; }
    public double k()                      { return k; }
    public double c()                      { return c; }
    public int confirmedRuns()             { return confirmedRuns; }
    public double p99Slack()               { return p99Slack; }
    public String droppedByKnob()          { return droppedByKnob; }
    public String confirmedConfigsJson()   { return confirmedConfigsJson; }
    public String slackByConfigJson()      { return slackByConfigJson; }
    public String refutedByJson()          { return refutedByJson; }
    public String oeisMatchJson()          { return oeisMatchJson; }
    public Instant createdAt()             { return createdAt; }
    public Instant updatedAt()             { return updatedAt; }
    public Instant promotedAt()            { return promotedAt; }

    // Targeted setters — mutated by the mapper during update cycles.
    public void setK(double k)                            { this.k = k; }
    public void setC(double c)                            { this.c = c; }
    public void setConfirmedRuns(int n)                   { this.confirmedRuns = n; }
    public void setP99Slack(double s)                     { this.p99Slack = s; }
    public void setDroppedByKnob(String knob)             { this.droppedByKnob = knob; }
    public void setConfirmedConfigsJson(String s)         { this.confirmedConfigsJson = s; }
    public void setSlackByConfigJson(String s)            { this.slackByConfigJson = s; }
    public void setRefutedByJson(String s)                { this.refutedByJson = s; }
    public void setOeisMatchJson(String s)                { this.oeisMatchJson = s; }
    public void setUpdatedAt(Instant t)                   { this.updatedAt = t; }
    public void setPromotedAt(Instant t)                  { this.promotedAt = t; }
}
