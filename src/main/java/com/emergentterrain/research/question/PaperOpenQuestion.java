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
 * Phase 33 lane T (T1, 2026-07-05) — one open question a published paper itself named as
 * untested. Papers already emit these ("whether k drifts toward the Cramér asymptotic value 1.0
 * at larger x was not tested", P-b72c-77627774) and the system discarded them — its best
 * questions were operator-reinjected by hand. This ledger persists them OUTSIDE the room
 * (rooms reset per app session; the ledger, like the conjecture pool, survives) so lane T2 can
 * serve them back into topic selection.
 *
 * <p>{@code answeredByPaperId}/{@code answeredAt} stay null until T2's coverage check marks a
 * question answered — reserved now so the schema doesn't need a second DDL pass
 * ([[feedback_ddl_nonnull_columns]]: additive-only ddl).
 */
@Entity
@Table(name = "paper_open_questions", indexes = {
        @Index(name = "idx_poq_family", columnList = "family"),
        @Index(name = "idx_poq_answered", columnList = "answeredAt")
})
public class PaperOpenQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 64)
    private String paperId;

    /** Topic family the question targets — the conjecture lhs for conjecture topics, else the topic name. */
    @Column(nullable = false, length = 128)
    private String family;

    @Column(nullable = false, length = 1000)
    private String question;

    /** RATE | BINDING | SENSITIVITY | SCOPE | OTHER — the follow-up class, for T2 ranking. */
    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 64)
    private String answeredByPaperId;

    private Instant answeredAt;

    protected PaperOpenQuestion() {}   // JPA

    public PaperOpenQuestion(String paperId, String family, String question, String kind) {
        this.paperId = paperId;
        this.family = family;
        this.question = question;
        this.kind = kind;
    }

    public UUID id()                  { return id; }
    public String paperId()           { return paperId; }
    public String family()            { return family; }
    public String question()          { return question; }
    public String kind()              { return kind; }
    public Instant createdAt()        { return createdAt; }
    public String answeredByPaperId() { return answeredByPaperId; }
    public Instant answeredAt()       { return answeredAt; }

    public void markAnswered(String byPaperId, Instant at) {
        this.answeredByPaperId = byPaperId;
        this.answeredAt = at;
    }
}
