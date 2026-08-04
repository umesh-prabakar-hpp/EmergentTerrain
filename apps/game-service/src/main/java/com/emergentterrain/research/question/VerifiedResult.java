package com.emergentterrain.research.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 33 lane C (C1, 2026-07-05) — one VERIFIED numerical fact that survived the publish
 * gates, with provenance. Rooms reset per app session and the conjecture pool keeps only the
 * live bounds — not the papers' measured trajectories — so the k(x) data points (tight
 * k=0.506@2e5 / 0.428@7e5 / 0.649@1.5e6) ended up spread across three archived rooms with no
 * live mechanism able to see all three. This ledger is that memory: results written at publish,
 * rendered back into the next same-family investigation as DATA.
 *
 * <p>{@code statement} is the tool's own verdict line (deterministic text, numbers included) —
 * renderable directly and parseable later; {@code kind} is CONSTANT | LADDER | TIGHT_BOUND.
 */
@Entity
@Table(name = "verified_results", indexes = {
        @Index(name = "idx_vr_family", columnList = "family")
})
public class VerifiedResult {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 64)
    private String paperId;

    @Column(nullable = false, length = 128)
    private String family;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false, length = 1500)
    private String statement;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected VerifiedResult() {}   // JPA

    public VerifiedResult(String paperId, String family, String kind, String statement) {
        this.paperId = paperId;
        this.family = family;
        this.kind = kind;
        this.statement = statement;
    }

    public UUID id()          { return id; }
    public String paperId()   { return paperId; }
    public String family()    { return family; }
    public String kind()      { return kind; }
    public String statement() { return statement; }
    public Instant createdAt(){ return createdAt; }
}
