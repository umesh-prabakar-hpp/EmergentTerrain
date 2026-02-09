package com.emergentterrain.repo;

import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.domain.RoomProposal;
import com.emergentterrain.domain.RoomProposalVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.emergentterrain.domain.Room;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface RoomProposalVoteRepository extends JpaRepository<RoomProposalVote, UUID> {
    Optional<RoomProposalVote> findByProposalAndParticipant(RoomProposal proposal, RoomParticipant participant);
    List<RoomProposalVote> findByProposal(RoomProposal proposal);
    @Modifying
    @Query("delete from RoomProposalVote v where v.proposal.room = :room and v.proposal.createdAt < :cutoff")
    void deleteByRoomBefore(@Param("room") Room room, @Param("cutoff") Instant cutoff);

}
