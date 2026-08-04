package com.emergentterrain.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 7 — a prediction market opened on a paper that carries a
 * pre-registered prediction. Other bots stake reputation on whether the
 * prediction will hold; resolution happens automatically when a later
 * paper in the same (room, domain) publishes after the market closes,
 * using the resolving paper's surpriseScore as the truth signal.
 *
 * Reputation is the currency for MVP; the phase doc's "wager compute
 * budget / grant access" idea is deferred until economic currency exists.
 *
 * Bounded staking — see EpistemicMarketService for the max stake fraction.
 */
@Entity
@Table(
        name = "epistemic_markets",
        indexes = {
                @Index(name = "idx_markets_room",   columnList = "room_id"),
                @Index(name = "idx_markets_domain", columnList = "room_id,domain"),
                @Index(name = "idx_markets_status", columnList = "status"),
                @Index(name = "idx_markets_hypothesis_paper", columnList = "hypothesis_paper_id")
        }
)
public class EpistemicMarket {

    public enum Status { OPEN, CLOSED, RESOLVED }

    /** Which way the prediction can resolve. */
    public enum StakeSide { CONFIRMS, REFUTES }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "hypothesis_paper_id", nullable = false, length = 64)
    private String hypothesisPaperId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 64)
    private String domain;

    @Column(nullable = false, length = 64)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Null until resolved. CONFIRMS = prediction held; REFUTES = surprise above threshold. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_side", length = 16)
    private StakeSide resolvedSide;

    /** PaperId of the paper that settled this market. Null until resolved. */
    @Column(name = "resolving_paper_id", length = 64)
    private String resolvingPaperId;

    protected EpistemicMarket() {}

    public EpistemicMarket(String hypothesisPaperId, UUID roomId, String domain, String topic,
                           Instant opensAt, Instant closesAt) {
        this.hypothesisPaperId = hypothesisPaperId;
        this.roomId            = roomId;
        this.domain            = domain;
        this.topic             = topic;
        this.opensAt           = opensAt;
        this.closesAt          = closesAt;
    }

    public UUID      getId()                { return id; }
    public String    getHypothesisPaperId() { return hypothesisPaperId; }
    public UUID      getRoomId()            { return roomId; }
    public String    getDomain()            { return domain; }
    public String    getTopic()             { return topic; }
    public Status    getStatus()            { return status; }
    public Instant   getOpensAt()           { return opensAt; }
    public Instant   getClosesAt()          { return closesAt; }
    public Instant   getResolvedAt()        { return resolvedAt; }
    public StakeSide getResolvedSide()      { return resolvedSide; }
    public String    getResolvingPaperId()  { return resolvingPaperId; }

    public void setStatus(Status s)               { this.status = s; }
    public void setResolvedAt(Instant t)          { this.resolvedAt = t; }
    public void setResolvedSide(StakeSide s)      { this.resolvedSide = s; }
    public void setResolvingPaperId(String id)    { this.resolvingPaperId = id; }
}
