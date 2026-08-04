package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 4c Piece 3 — persistent counters for a registered prompt
 * template variant. One row per {@code (templateId, versionTag)} pair.
 *
 * <p>The in-memory {@link PromptTemplateRegistry} is still the runtime
 * source of truth for the template body and the active-pick decision.
 * This entity exists so subsequent piece-3 PRs can attach narration /
 * replication counters to a variant, run an aggregate two-proportion
 * z-test, and persist the {@code ROLLED_BACK} flip across restarts.
 *
 * <p>Per the phase-4c-piece-3 design doc (2026-05-20), Decision 10
 * was revised — counters are aggregated across domains instead of
 * sharded into a per-domain sibling table. The reason is corpus rate:
 * at ~38 papers/day (the observed 2026-05-06 → 2026-05-20 rate),
 * per-domain isolation makes the rollback-threshold sample size
 * unreachable inside a year. When corpus rate climbs to ~200
 * papers/day, a follow-up adds the sibling table and re-enables
 * per-domain isolation; the trigger is documented in
 * {@code phase-4c-piece-3.md}.
 *
 * <p>Schema is created by Hibernate {@code ddl-auto: update} on next
 * startup; the row population is idempotent (keyed on the unique
 * constraint below) so multiple restarts don't duplicate entries.
 */
@Entity
@Table(
        name = "prompt_template_variants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_prompt_template_variant",
                        columnNames = {"template_id", "version_tag"})
        },
        indexes = {
                @Index(name = "idx_prompt_template_status",
                        columnList = "template_id,status")
        }
)
public class PromptTemplateVariant {

    /**
     * Lifecycle:
     * <ul>
     *   <li>{@code ACTIVE} — eligible for selection by piece-3's
     *       epsilon-greedy {@code pickActive}. Default on creation.</li>
     *   <li>{@code ROLLED_BACK} — variant's rolling-window failure
     *       rate exceeded baseline by the configured margin; the
     *       selector excludes it. Set by the piece-3 rollback judge
     *       (PR4) or by an operator.</li>
     *   <li>{@code RETIRED} — operator explicitly removed the variant
     *       from rotation (e.g., to free a slot for a successor).
     *       Distinguished from ROLLED_BACK so retired variants don't
     *       confuse the audit log.</li>
     * </ul>
     */
    public enum Status { ACTIVE, ROLLED_BACK, RETIRED }

    @Id
    @GeneratedValue
    private UUID id;

    /** e.g. {@code "narration.primary"}; matches {@link PromptTemplate#id()}. */
    @Column(name = "template_id", nullable = false, length = 128)
    private String templateId;

    /** e.g. {@code "v1"} / {@code "v1-from-critique"}; matches {@link PromptTemplate#version()}. */
    @Column(name = "version_tag", nullable = false, length = 64)
    private String versionTag;

    /**
     * Rendered template body. Mirrors {@link PromptTemplate#body()} at
     * registration time. Stored here so a rolled-back variant's prose
     * can be inspected post-hoc without git archaeology — the registry
     * itself only keeps active variants in memory after a rebuild.
     */
    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /**
     * Cumulative count of narrations this variant produced. Incremented
     * by piece-3 PR3 on each terminal replication outcome (any of the
     * three counters below also gets a +1). Used as the denominator for
     * the rollback failure-rate calculation in PR4.
     */
    @Column(name = "papers_narrated", nullable = false)
    private long papersNarrated = 0L;

    /**
     * Cumulative count of papers narrated by this variant whose
     * replication later resolved as CONFIRMED. Numerator's complement
     * in the rollback test.
     */
    @Column(name = "replications_confirmed", nullable = false)
    private long replicationsConfirmed = 0L;

    /**
     * Cumulative count of papers narrated by this variant whose
     * replication later resolved as FAILED / DISPUTED /
     * DISPUTE_ABANDONED. Numerator of the rollback failure-rate test.
     */
    @Column(name = "replications_failed", nullable = false)
    private long replicationsFailed = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null while {@link Status#ACTIVE}. Set when status flips to ROLLED_BACK or RETIRED. */
    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    /** JPA needs a no-arg constructor. */
    protected PromptTemplateVariant() {}

    public PromptTemplateVariant(String templateId, String versionTag,
                                  String body, Instant createdAt) {
        this.templateId = templateId;
        this.versionTag = versionTag;
        this.body       = body;
        this.createdAt  = createdAt;
    }

    public UUID    getId()                     { return id; }
    public String  getTemplateId()             { return templateId; }
    public String  getVersionTag()             { return versionTag; }
    public String  getBody()                   { return body; }
    public long    getPapersNarrated()         { return papersNarrated; }
    public long    getReplicationsConfirmed()  { return replicationsConfirmed; }
    public long    getReplicationsFailed()     { return replicationsFailed; }
    public Status  getStatus()                 { return status; }
    public Instant getCreatedAt()              { return createdAt; }
    public Instant getRolledBackAt()           { return rolledBackAt; }

    /**
     * Update the body when re-registering an existing {@code (templateId,
     * versionTag)} pair. Counters and lifecycle fields are preserved so
     * a body edit doesn't reset the variant's stats.
     */
    public void setBody(String body) { this.body = body; }

    public void setStatus(Status status) {
        this.status = status;
        if (status != Status.ACTIVE && rolledBackAt == null) {
            rolledBackAt = Instant.now();
        }
    }

    /** Piece-3 PR3 will call these from the terminal-outcome path. */
    public void incrementPapersNarrated()        { this.papersNarrated++; }
    public void incrementReplicationsConfirmed() { this.replicationsConfirmed++; }
    public void incrementReplicationsFailed()    { this.replicationsFailed++; }
}
