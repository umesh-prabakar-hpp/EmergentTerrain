package com.emergentterrain.repo;

import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.domain.RoomProposal;
import com.emergentterrain.domain.RoomProposalVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomProposalVoteRepository extends JpaRepository<RoomProposalVote, UUID> {
    Optional<RoomProposalVote> findByProposalAndParticipant(RoomProposal proposal, RoomParticipant participant);
}
