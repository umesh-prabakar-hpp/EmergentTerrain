package com.emergentterrain.research;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
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
    public long         tick()           { return tick; }
    public Instant      publishedAt()    { return publishedAt; }
    public int          generation()     { return generation; }
    public double       anomalyZScore()  { return anomalyZScore; }
    public String       aiModel()        { return aiModel; }
    public String       llmCallPath()    { return llmCallPath; }

    // Narrow setters used by the DISPUTED flow only.
    public void setStatus(String status)         { this.status = status; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
}
