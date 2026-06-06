package com.emergentterrain.research.memory;

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
 * Phase 31 §31.1 — one row of the typed, append-only, content-addressed
 * research memory (the mem0/agentmemory adoption). New knowledge never
 * overwrites old: a duplicate capture (same {@code contentHash}) reinforces
 * the existing row; a superseding capture (31.2's consolidation, not the 31.1
 * write path) chains via {@code supersedesId} and flips the old row's
 * {@code isLatest} — so "what did we believe on date X" stays answerable.
 *
 * <p>Created additively by {@code ddl-auto=update} like the frontier tables
 * ([[feedback_ddl_nonnull_columns]] discipline: every non-null column carries
 * a value at insert; {@code type} is a String, not {@code @Enumerated}, so the
 * taxonomy can grow without a stale CHECK constraint). The {@code embedding}
 * column is deliberately ABSENT in v1 — 31.3 is index-first; the schema gains
 * it if/when a vector tier is ever justified.
 */
@Entity
@Table(name = "research_memory",
        uniqueConstraints = @UniqueConstraint(name = "uq_memory_content_hash",
                columnNames = {"content_hash"}),
        indexes = {
                @Index(name = "ix_memory_room_topic", columnList = "room_id, topic"),
                @Index(name = "ix_memory_type_latest", columnList = "type, is_latest")
        })
public class ResearchMemoryEntity {

    /** The v1 type taxonomy (String-stored; growable without DDL surgery). */
    public static final String TYPE_FACT = "FACT";
    public static final String TYPE_LESSON = "LESSON";
    public static final String TYPE_PROCEDURAL = "PROCEDURAL";
    public static final String TYPE_INSIGHT = "INSIGHT";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    /** SHA-256 hex of {@code type + "\n" + content} — the dedup address. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** mem0-style per-entry quality signal; retrieval (31.3) ranks on it. */
    @Column(nullable = false)
    private int strength = 5;

    @Column(nullable = false)
    private int reinforcements = 0;

    @Column(nullable = false)
    private int version = 1;

    /** Set by 31.2 consolidation only — the 31.1 write path never supersedes. */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    /** Resolvable provenance: paper ids / gate names / log refs, comma-joined. */
    @Column(name = "source_refs", columnDefinition = "text")
    private String sourceRefs;

    private String topic;

    private String domain;

    /** mem0's user/agent/run scoping triple as ET's room/bot/run. */
    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "bot_id")
    private String botId;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_accessed_at", nullable = false)
    private Instant lastAccessedAt = Instant.now();

    @Column(name = "is_latest", nullable = false)
    private boolean isLatest = true;

    protected ResearchMemoryEntity() {}

    public ResearchMemoryEntity(String type, String content, String contentHash,
            String sourceRefs, String topic, String domain, UUID roomId,
            String botId, String runId) {
        this.type = type;
        this.content = content;
        this.contentHash = contentHash;
        this.sourceRefs = sourceRefs;
        this.topic = topic;
        this.domain = domain;
        this.roomId = roomId;
        this.botId = botId;
        this.runId = runId;
    }

    public void reinforce() {
        this.reinforcements++;
        this.lastAccessedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String type() { return type; }
    public String content() { return content; }
    public String contentHash() { return contentHash; }
    public int strength() { return strength; }
    public int reinforcements() { return reinforcements; }
    public String sourceRefs() { return sourceRefs; }
    public String topic() { return topic; }
    public UUID roomId() { return roomId; }
    public String runId() { return runId; }
    public boolean isLatest() { return isLatest; }
}
