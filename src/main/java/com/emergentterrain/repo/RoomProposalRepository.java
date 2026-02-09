package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface RoomProposalRepository extends JpaRepository<RoomProposal, UUID> {
    List<RoomProposal> findTop10ByRoomOrderByCreatedAtDesc(Room room);
    void deleteByRoomAndCreatedAtBefore(Room room, Instant cutoff);

    @Modifying
    @Query("delete from RoomProposal p where p.room = :room and p.createdAt < :cutoff")
    void deleteByRoomBefore(@Param("room") Room room, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("update RoomProposal p set p.status = 'REJECTED' where p.room = :room and p.status = 'PENDING' and p.expiresAt < :now")
    int rejectExpiredPending(@Param("room") Room room, @Param("now") Instant now);
}
