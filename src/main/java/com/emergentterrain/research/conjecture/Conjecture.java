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

    // Phase 30 G2 — optional second affine term: lhs ≤ k*rhs + k2*rhs2 + c. rhs2 NULL (k2 0.0) = the
    // backward-compat one-term bound. DDL (run manually; ddl-auto=update also adds these):
    //   ALTER TABLE conjectures ADD COLUMN IF NOT EXISTS rhs2 VARCHAR;
    //   ALTER TABLE conjectures ADD COLUMN IF NOT EXISTS k2 DOUBLE PRECISION NOT NULL DEFAULT 0;
    private String rhs2;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private double k2 = 0.0;

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

    /**
     * Phase 30 Wave 0 — last time this conjecture was selected for a paper
     * (consumption mark). Drives investigation rotation in
     * {@link ConjectureMinerService#nextN} so the same refuted conjecture is not
     * re-served every cycle. Nullable (never investigated); additive column.
     *
     * <p>{@code updatable = false}: ONLY {@code ConjectureRepository.stampInvestigated}
     * (JPQL bulk UPDATE, which bypasses entity column metadata) may write this after
     * insert. Entity-level saves omit the column entirely, so the miner's pass-long
     * re-save loop can never clobber a stamp landing mid-pass. The freshen() re-read
     * narrowed that race to the per-row read→apply→save window but could not close
     * it — P-b72c-121/122 (2026-06-04 21:02): cycle #1's stamp at 21:02:23.9 fell
     * between pass #1's freshen and save of the same row, was erased, and the
     * NULL-stamped row then won the updatedAt tiebreak and was re-served 15 s later.
     *
     * <p><b>Verification status (2026-06-04 22:30 session, first run on this fix):</b>
     * 6/6 queued conjectures distinct, all 6 stamps persisted, 77/77 tier-1 rows
     * stamped — the watch item passed. CAVEAT: all six stamps happened to land
     * BETWEEN mine passes (each pass ~10 s of every ~5 min ⇒ ~3% mid-pass odds per
     * stamp, ~17% that any of the six exercised the exact race window), so the
     * empty-pool-era mid-pass collision path has not been empirically stressed.
     * The guarantee is structural — entity UPDATE statements simply no longer
     * contain this column — not yet battle-tested. If a same-conjecture pair ever
     * again publishes ~15 s apart shortly after startup, re-open this: the residual
     * suspect would be the pure selection race (two cycles both reading nextN()
     * before either stamp commits), which is a different, much narrower window.
     */
    @jakarta.persistence.Column(updatable = false)
    private Instant lastInvestigatedAt;

    /**
     * Phase 30 Wave 7 — 30.6: set when this counterexample-refuted bound survived
     * the adversarial promotion gate (held in its confirmed regime AND its break
     * replicated across N fresh refutation attempts, no OEIS match) — making it a
     * promotable UNEXPLAINED_OBSERVATION candidate the paper pipeline can select.
     * Nullable (never promoted); additive column. {@code updatable = false} for the
     * same reason as {@code lastInvestigatedAt}: only the targeted JPQL stamp
     * ({@code ConjectureRepository.stampPromotedAnomaly}) writes it, so the miner's
     * pass-long re-save loop can never clobber a mid-pass stamp.
     */
    @jakarta.persistence.Column(updatable = false)
    private Instant promotedAnomalyAt;

    /**
     * 2026-06-09 — set when a conjecture-investigation paper's professor proved
     * THIS (lhs,rhs) bound HELD at the failure region via verify_conjecture, and
     * the write-back service revived/inserted it with the professor's tuned (k,c).
     * Durable audit trail of the discovery→pool loop. {@code updatable = false}
     * for the same reason as the other stamps: only the targeted JPQL stamp
     * ({@code ConjectureRepository.stampInvestigationVerified}) writes it, so the
     * miner's pass-long re-save loop can never clobber it.
     */
    @jakarta.persistence.Column(updatable = false)
    private Instant investigationVerifiedAt;

    /**
     * Phase 30 integration step 2 — when non-null, this conjecture was synthesized
     * by {@link ConjectureComposer} as a sound 1-step composition of two parent
     * bounds rather than mined from sample windows. Value is the two parent
     * descriptor keys it was composed from (e.g. {@code "a <= +k* b  ∘  b <= +k* c"}).
     * Lets the paper pipeline tell a derivable-but-non-trivial claim from an
     * empirically-mined bound, so the narrator doesn't present a derived
     * consequence as an independent observation. Nullable (mined conjectures leave
     * it null); additive column.
     */
    private String derivedFrom;

    /**
     * 2026-06-10 (lever D) — result-frame discriminator. {@code null}/"BOUND" = a one-sided inequality
     * {@code lhs ≤ k·rhs + c} (the mined default). "CONSTANT" = a measured limit {@code lhs → c} (frame
     * 1/3: twin_vs_prime_sq → 2C₂, or a Wigert growth constant). "OSCILLATION" = a centred, sign-changing
     * quantity with bounded envelope {@code c} (frame 2: mertens_delta). A non-BOUND row is a verified
     * MEASUREMENT, not an inequality, so the miner does NOT re-test it as a bound (a one-sided test would
     * counterexample a converging/oscillating quantity). Nullable; additive column; null treated as BOUND.
     */
    private String kind;

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
    public String rhs2()                   { return rhs2; }   // G2: null = one-term
    public double k2()                     { return k2; }
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
    public Instant lastInvestigatedAt()    { return lastInvestigatedAt; }
    public Instant promotedAnomalyAt()     { return promotedAnomalyAt; }
    public Instant investigationVerifiedAt() { return investigationVerifiedAt; }
    public String derivedFrom()            { return derivedFrom; }
    /** Result frame; null persists as "BOUND". See the {@code kind} field. */
    public String kind()                   { return kind == null ? "BOUND" : kind; }
    public boolean isBound()               { return kind == null || "BOUND".equals(kind); }

    // Targeted setters — mutated by the mapper during update cycles.
    public void setKind(String kind)                      { this.kind = kind; }
    public void setK(double k)                            { this.k = k; }
    public void setC(double c)                            { this.c = c; }
    public void setRhs2(String rhs2)                      { this.rhs2 = rhs2; }   // G2
    public void setK2(double k2)                          { this.k2 = k2; }
    public void setConfirmedRuns(int n)                   { this.confirmedRuns = n; }
    public void setP99Slack(double s)                     { this.p99Slack = s; }
    public void setDroppedByKnob(String knob)             { this.droppedByKnob = knob; }
    public void setConfirmedConfigsJson(String s)         { this.confirmedConfigsJson = s; }
    public void setSlackByConfigJson(String s)            { this.slackByConfigJson = s; }
    public void setRefutedByJson(String s)                { this.refutedByJson = s; }
    public void setOeisMatchJson(String s)                { this.oeisMatchJson = s; }
    public void setUpdatedAt(Instant t)                   { this.updatedAt = t; }
    public void setPromotedAt(Instant t)                  { this.promotedAt = t; }
    public void setLastInvestigatedAt(Instant t)          { this.lastInvestigatedAt = t; }
    public void setPromotedAnomalyAt(Instant t)           { this.promotedAnomalyAt = t; }
    public void setDerivedFrom(String s)                  { this.derivedFrom = s; }
}
