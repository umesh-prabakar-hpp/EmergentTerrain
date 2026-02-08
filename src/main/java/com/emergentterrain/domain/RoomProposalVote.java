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

    @ManyToOne(optional = false)
    private RoomParticipant participant;

    @Column(nullable = false)
    private boolean approve;

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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
