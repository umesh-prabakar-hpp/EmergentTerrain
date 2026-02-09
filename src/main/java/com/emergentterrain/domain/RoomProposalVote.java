package com.emergentterrain.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_proposal_votes")
public class RoomProposalVote {
    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    private RoomProposal proposal;

    @ManyToOne(optional = true)
    @JoinColumn(nullable = true)
    private RoomParticipant participant;

    @Column(nullable = false)
    private boolean approve;

    @Column(nullable = false)
    private boolean botVote = false;

    private String botId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public RoomProposal getProposal() {
        return proposal;
    }

    public void setProposal(RoomProposal proposal) {
        this.proposal = proposal;
    }

    public RoomParticipant getParticipant() {
        return participant;
    }

    public void setParticipant(RoomParticipant participant) {
        this.participant = participant;
    }

    public boolean isApprove() {
        return approve;
    }

    public void setApprove(boolean approve) {
        this.approve = approve;
    }

    public boolean isBotVote() {
        return botVote;
    }

    public void setBotVote(boolean botVote) {
        this.botVote = botVote;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
