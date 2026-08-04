package com.emergentterrain.research;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.emergentterrain.research.publication.PublicationLabel;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent paper produced by the research pipeline. Source of truth lives in the DB
 * (research_papers + research_paper_reviewers element table) since Phase 0; the markdown
 * file under researchOutputDir is now a human-readable side effect, not a substitute.
 *
 * Record-style accessors (paperId(), domain(), …) are preserved so call sites that were
 * written against the old record do not need to change. Jackson picks up the fields
 * directly via @JsonAutoDetect rather than via getter naming.
 *
 * Mutability is intentionally narrow: only status and conclusion are setter-exposed so
 * that the DISPUTED flow in UniversityService.markPaperDisputed can rewrite a paper
 * in place instead of re-allocating it. All other fields are write-once via the
 * publish constructor.
 */
@Entity
@Table(name = "research_papers")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ResearchPaper {

    @Id
    private String paperId;

    @Column(nullable = false)
    private UUID roomId;

    private String authorBotId;
    private String domain;
    private String topic;
    private String title;

    @Column(columnDefinition = "text")
    private String hypothesis;

    private String methodology;

    @Column(columnDefinition = "text")
    private String findings;

    @Column(columnDefinition = "text")
    private String conclusion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "research_paper_reviewers",
            joinColumns = @JoinColumn(name = "paper_id")
    )
    @Column(name = "reviewer_bot_id")
    private List<String> reviewerBotIds = new ArrayList<>();

    private double peerScore;
    private String status;

    /**
     * Reader-facing quality tier. Unlike {@link #status}, this is not a workflow
     * disposition: a clean PUBLISHED result can honestly remain an internal note.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "publication_label", nullable = false, length = 64,
            columnDefinition = "varchar(64) not null default 'INTERNAL_RESEARCH_NOTE'")
    private PublicationLabel publicationLabel = PublicationLabel.INTERNAL_RESEARCH_NOTE;
    private long tick;
    private Instant publishedAt;
    private int generation;

    /** z-score of peerScore vs room's domain+topic distribution; 0.0 if < 5 prior papers. */
    private double anomalyZScore;

    /** LLM that wrote the narration; null when template fallback was used. */
    private String aiModel;

    /** Audit trail of LLM calls (e.g. "audit:4b:2gaps → prof:llama-70b:ok"). */
    @Column(columnDefinition = "text")
    private String llmCallPath;

    /** Phase 1 Iter 4 — optional FK to the {@link ResearchProgram} this paper belongs to. */
    @Column(name = "program_id")
    private UUID programId;

    /** Phase 3 Iter 8 — optional FK to the {@link University} that hosted the author. */
    @Column(name = "university_id")
    private UUID universityId;

    /** Phase 3 Iter 8 — optional FK to the {@link Journal} the paper was submitted to. */
    @Column(name = "journal_id")
    private UUID journalId;

    /**
     * Phase 4 Iter 10 — DOMAIN papers are the simulation's actual research
     * output; META papers are analyses of the system itself. Defaults to
     * DOMAIN so existing rows read back unchanged.
     */
    // 2026-05-29 — @Convert (not @Enumerated) so Hibernate generates NO `check (kind in (...))`
    // constraint: such checks go stale on enum additions and ddl-auto can't update them (a stale
    // DOMAIN,META check blocked CONJECTURE_INVESTIGATION inserts). length=32 (not 16):
    // CONJECTURE_INVESTIGATION is 24 chars; the live column was ALTERed to varchar(32) since
    // ddl-auto does not widen existing columns — this keeps fresh DBs correct.
    @Convert(converter = PaperKindConverter.class)
    @Column(name = "kind", nullable = false, length = 32)
    private PaperKind kind = PaperKind.DOMAIN;

    /**
     * Phase 23.6 — epistemic claim level. Defaults to {@link EvidenceLabel#CONJECTURE}
     * so a paper has to earn anything stronger (mechanism + adversarial check for
     * EMPIRICAL_RESULT; formal proof for THEOREM). The {@code columnDefinition}
     * carries a Postgres-level {@code DEFAULT 'CONJECTURE'} so Hibernate's
     * {@code ddl-auto: update} emits {@code ALTER TABLE ... ADD COLUMN
     * evidence_label varchar(32) NOT NULL DEFAULT 'CONJECTURE'} on first
     * start against an existing table — existing rows backfill atomically
     * (matches the {@code dispute_attempts} migration pattern from Phase 21.2,
     * and avoids the manual-ALTER hack that the {@code narration_input_tokens}
     * columns needed). Legacy NULLs (if any survive the migration) are
     * coerced to CONJECTURE by {@link #setEvidenceLabel}.
     */
    // 2026-05-29 — @Convert (not @Enumerated): no auto-generated `check (evidence_label in (...))`
    // constraint (the old one was stale — missing KNOWN_RESULT — and ddl-auto can't update it).
    @Convert(converter = EvidenceLabelConverter.class)
    @Column(name = "evidence_label", nullable = false, length = 32,
            columnDefinition = "varchar(32) not null default 'CONJECTURE'")
    private EvidenceLabel evidenceLabel = EvidenceLabel.CONJECTURE;

    /**
     * Phase 4c Piece 2 gate 3 — identifier of the prompt template variant whose
     * narration produced this paper's findings. Stable across template body
     * mutations: {@code narration.primary} stays the same when {@code v1 → v2}.
     * Null on legacy rows persisted before this column was added.
     */
    @Column(name = "narration_template_id", length = 64)
    private String narrationTemplateId;

    /**
     * Phase 4c Piece 2 gate 3 — version tag of the prompt template variant. Used
     * with {@link #narrationTemplateId} as the {@code (id, version)} pair Phase
     * 4c Piece 3 will key per-variant rollback statistics on. Null on legacy
     * rows.
     */
    /**
     * Width matches narration_template_id (64). The original 32 capped before
     * Phase 7 F2's longer variant ids landed (e.g. "v3-structured-findings-with-discussion"
     * is 38 chars). Hibernate ddl-auto: update widens the column on next start;
     * existing rows stay valid.
     */
    @Column(name = "narration_template_version", length = 64)
    private String narrationTemplateVersion;

    /**
     * Phase 6 D2 — optional Discussion section. Null when D2 was disabled at
     * narration time or when the narrator's JSON did not include the field.
     * Same nullability semantics as {@link #narrationTemplateId} — legacy
     * rows persisted before this column existed remain null.
     */
    @Column(name = "discussion", columnDefinition = "text")
    private String discussion;

    /**
     * Phase 6 D2 — optional Related Work section. Null when D2 was disabled
     * at narration time or when the narrator's JSON did not include the field.
     */
    @Column(name = "related_work", columnDefinition = "text")
    private String relatedWork;

    /**
     * Phase 7 F5.4 — Tier 2 audit trail of the agentic professor stage. Stores
     * the LLM's tool-call trace as a JSON-serialised {@code List<ToolInvocation>}.
     * Null when the professor stage either did not run (JSON-path / Ollama
     * fallback) or ran without issuing any tool call. Persisted independently
     * of the {@code findings} prose so the audit record survives even when the
     * LLM cherry-picked which results to narrate.
     */
    @Column(name = "tool_call_trace", columnDefinition = "text")
    private String toolCallTrace;

    /** Canonical locked-study identity retained for allocation-time clone screening. */
    @Column(name = "protocol_fingerprint", length = 2048)
    private String protocolFingerprint;

    /**
     * Phase 7 F7.2 — multi-reviewer panel outcome serialised as JSON. Carries the
     * aggregated median grades, agreement score, consensus cited/contradiction
     * lists, deduped recommendations, and the raw N reviewer responses. Null
     * when the panel did not run.
     */
    @Column(name = "panel_outcome", columnDefinition = "text")
    private String panelOutcome;

    /**
     * Phase 7 F7.2 — composite reviewer-agreement score from the panel. Cached
     * out of {@link #panelOutcome} into its own column so DISPUTED routing
     * (F7.3) can query / index on it without parsing the JSON each time. Null
     * when the panel did not run.
     */
    @Column(name = "reviewer_agreement")
    private Double reviewerAgreement;

    /**
     * Phase 20.4 — narration-orchestration envelope, copied from
     * {@link NarrationResult} at publish time. Five columns so the call's
     * cost (tokens), duration (timing), and terminal outcome (status enum
     * persisted as the enum name) are queryable from SQL without parsing
     * the {@code call_path} string. All five default null/null/0/0/null on
     * legacy rows persisted before this column block existed.
     *
     * <p>{@code narration_status} stores the {@link NarrationResult.OutcomeStatus}
     * enum's {@code name()}; persisted as VARCHAR rather than an SMALLINT
     * ordinal so adding a new enum value never reindexes existing rows.
     * Width 32 accommodates the longest current value
     * ({@code ALL_MODELS_BLOCKED}, 18 chars) with headroom.
     */
    @Column(name = "narration_started_at")
    private java.time.Instant narrationStartedAt;

    @Column(name = "narration_completed_at")
    private java.time.Instant narrationCompletedAt;

    @Column(name = "narration_input_tokens", nullable = false)
    private long narrationInputTokens;

    @Column(name = "narration_output_tokens", nullable = false)
    private long narrationOutputTokens;

    @Column(name = "narration_status", length = 32)
    private String narrationStatus;

    /**
     * 2026-05-29 — pipeline/quality-health axis ({@link ReviewState} name),
     * orthogonal to {@link #status}. Distinguishes incomplete-but-sound from
     * defective revisions, and gate-flagged from genuine nulls. Nullable;
     * pre-2026-05-29 rows read back {@code null}. Set via {@link #setReviewState}
     * after construction, mirroring {@link #narrationStatus}.
     */
    @Column(name = "review_state", length = 32)
    private String reviewState;

    /**
     * 2026-05-29 — INCOMPLETE re-queue. Number of automated re-narration attempts
     * this paper slot has had. Carried forward across the in-place re-publish so a
     * rework that comes out INCOMPLETE again lands at the cap and is not re-queued.
     * 0 for papers that were never reworked.
     */
    @Column(name = "rework_attempts", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")  // 2026-05-29 — ddl-auto ADD COLUMN ... DEFAULT 0 so it succeeds on the already-populated table (backfills existing rows); without it the NOT NULL add fails and the column is never created
    private int reworkAttempts;

    /**
     * 2026-05-29 — INCOMPLETE re-queue. Serialized {@link ReworkDraftSnapshot} of
     * the originating draft, persisted ONLY for {@code (NEEDS_REVISION, INCOMPLETE)}
     * papers still under the attempt cap, so the sweep can re-narrate faithfully
     * from the true seed inputs (rawFindings, sweep params, pre-registration).
     * Null for every other paper, and cleared once consumed.
     */
    @Column(name = "rework_draft_json", columnDefinition = "TEXT")
    private String reworkDraftJson;

    /**
     * Phase 21 §21.2 — replicator bot id captured at the DISPUTED transition,
     * so the {@code scanOrphanedDisputes} sweep can re-ping the replicator
     * side too instead of refreshing only the original author. Null on
     * legacy rows persisted before this column existed and on non-DISPUTED
     * papers (most rows).
     */
    @Column(name = "dispute_replicator_bot_id")
    private String disputeReplicatorBotId;

    /**
     * Phase 21 §21.2 — per-paper sweep-attempt counter. Bumped each time
     * {@code scanOrphanedDisputes} re-fires reconciliation for this paper.
     * Once it reaches {@code dispute-max-attempts} (default 6 ≈ 6 h at the
     * current 1 h cadence), the paper's status is demoted to
     * {@code DISPUTE_ABANDONED} and re-firing stops — the 17-unique-ID
     * orphan loop documented in REVIEW_REPORT a3289005 closes naturally.
     * NOT NULL so SQL aggregations don't have to coalesce; legacy rows
     * default to 0 via the column-level DEFAULT so the ddl-auto:update
     * `ADD COLUMN ... NOT NULL` succeeds on already-populated tables.
     */
    @Column(name = "dispute_attempts", nullable = false,
            columnDefinition = "integer not null default 0")
    private int disputeAttempts;

    /** JPA needs a no-arg constructor. */
    protected ResearchPaper() {}

    /**
     * Publish constructor. roomId must be supplied so the paper can be queried by room
     * after Phase 0 — the legacy record had no roomId because it was keyed by an
     * in-memory map.
     */
    public ResearchPaper(String paperId,
                         UUID roomId,
                         String authorBotId,
                         String domain,
                         String topic,
                         String title,
                         String hypothesis,
                         String methodology,
                         String findings,
                         String conclusion,
                         List<String> reviewerBotIds,
                         double peerScore,
                         String status,
                         long tick,
                         Instant publishedAt,
                         int generation,
                         double anomalyZScore,
                         String aiModel,
                         String llmCallPath) {
        this.paperId        = paperId;
        this.roomId         = roomId;
        this.authorBotId    = authorBotId;
        this.domain         = domain;
        this.topic          = topic;
        this.title          = title;
        this.hypothesis     = hypothesis;
        this.methodology    = methodology;
        this.findings       = findings;
        this.conclusion     = conclusion;
        this.reviewerBotIds = reviewerBotIds != null ? new ArrayList<>(reviewerBotIds) : new ArrayList<>();
        this.peerScore      = peerScore;
        this.status         = status;
        this.tick           = tick;
        this.publishedAt    = publishedAt;
        this.generation     = generation;
        this.anomalyZScore  = anomalyZScore;
        this.aiModel        = aiModel;
        this.llmCallPath    = llmCallPath;
    }

    // Record-style accessors. Names preserved so Phase 0 doesn't churn 40+ call sites.
    public String       paperId()        { return paperId; }
    public UUID         roomId()         { return roomId; }
    public String       authorBotId()    { return authorBotId; }
    public String       domain()         { return domain; }
    public String       topic()          { return topic; }
    public String       title()          { return title; }
    public String       hypothesis()     { return hypothesis; }
    public String       methodology()    { return methodology; }
    public String       findings()       { return findings; }
    public String       conclusion()     { return conclusion; }
    public List<String> reviewerBotIds() { return reviewerBotIds; }
    public double       peerScore()      { return peerScore; }
    public String       status()         { return status; }
    public PublicationLabel publicationLabel() { return publicationLabel; }
    public long         tick()           { return tick; }
    public Instant      publishedAt()    { return publishedAt; }
    public int          generation()     { return generation; }
    public double       anomalyZScore()  { return anomalyZScore; }
    public String       aiModel()        { return aiModel; }
    public String       llmCallPath()    { return llmCallPath; }
    public UUID         programId()      { return programId; }
    public UUID         universityId()   { return universityId; }
    public UUID         journalId()      { return journalId; }
    public PaperKind    kind()           { return kind; }
    public EvidenceLabel evidenceLabel() { return evidenceLabel; }
    public String       narrationTemplateId()      { return narrationTemplateId; }
    public String       narrationTemplateVersion() { return narrationTemplateVersion; }
    public String       discussion()               { return discussion; }
    public String       relatedWork()              { return relatedWork; }
    public String       toolCallTrace()            { return toolCallTrace; }
    public String       protocolFingerprint()       { return protocolFingerprint; }
    public String       panelOutcome()             { return panelOutcome; }
    public Double       reviewerAgreement()        { return reviewerAgreement; }
    public java.time.Instant narrationStartedAt()   { return narrationStartedAt; }
    public java.time.Instant narrationCompletedAt() { return narrationCompletedAt; }
    public long         narrationInputTokens()      { return narrationInputTokens; }
    public long         narrationOutputTokens()     { return narrationOutputTokens; }
    public String       narrationStatus()           { return narrationStatus; }
    public String       reviewState()               { return reviewState; }
    public int          reworkAttempts()            { return reworkAttempts; }
    public String       reworkDraftJson()           { return reworkDraftJson; }
    public String       disputeReplicatorBotId()    { return disputeReplicatorBotId; }
    public int          disputeAttempts()           { return disputeAttempts; }

    // Narrow setters used by the DISPUTED flow + program / institution attachment.
    public void setStatus(String status)             { this.status = status; }
    public void setPublicationLabel(PublicationLabel label) {
        this.publicationLabel = label != null ? label : PublicationLabel.INTERNAL_RESEARCH_NOTE;
    }
    public void setConclusion(String conclusion)     { this.conclusion = conclusion; }
    public void setProgramId(UUID programId)         { this.programId = programId; }
    public void setUniversityId(UUID universityId)   { this.universityId = universityId; }
    public void setJournalId(UUID journalId)         { this.journalId = journalId; }
    public void setKind(PaperKind kind)              { this.kind = kind != null ? kind : PaperKind.DOMAIN; }
    public void setEvidenceLabel(EvidenceLabel label) {
        this.evidenceLabel = label != null ? label : EvidenceLabel.CONJECTURE;
    }
    public void setNarrationTemplateId(String id)           { this.narrationTemplateId = id; }
    public void setNarrationTemplateVersion(String version) { this.narrationTemplateVersion = version; }
    public void setDiscussion(String discussion)     { this.discussion = discussion; }
    public void setRelatedWork(String relatedWork)   { this.relatedWork = relatedWork; }
    public void setToolCallTrace(String toolCallTrace) { this.toolCallTrace = toolCallTrace; }
    public void setProtocolFingerprint(String value) { this.protocolFingerprint = value; }
    public void setPanelOutcome(String panelOutcome)   { this.panelOutcome = panelOutcome; }
    public void setReviewerAgreement(Double v)         { this.reviewerAgreement = v; }
    // Phase 20.4 — narration envelope setters; called from UniversityService.publishPaper.
    public void setNarrationStartedAt(java.time.Instant v)   { this.narrationStartedAt = v; }
    public void setNarrationCompletedAt(java.time.Instant v) { this.narrationCompletedAt = v; }
    public void setNarrationInputTokens(long v)              { this.narrationInputTokens = v; }
    public void setNarrationOutputTokens(long v)             { this.narrationOutputTokens = v; }
    public void setNarrationStatus(String v)                 { this.narrationStatus = v; }
    public void setReviewState(String v)                     { this.reviewState = v; }
    public void setReworkAttempts(int v)                     { this.reworkAttempts = v; }
    public void setReworkDraftJson(String v)                 { this.reworkDraftJson = v; }
    public void setDisputeReplicatorBotId(String v)          { this.disputeReplicatorBotId = v; }
    public void setDisputeAttempts(int v)                    { this.disputeAttempts = v; }
    public int  incrementDisputeAttempts()                   { return ++this.disputeAttempts; }
}
