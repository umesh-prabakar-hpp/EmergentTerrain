package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

public interface RoomProposalRepository extends JpaRepository<RoomProposal, UUID> {
    List<RoomProposal> findTop10ByRoomOrderByCreatedAtDesc(Room room);
    void deleteByRoomAndCreatedAtBefore(Room room, Instant cutoff);
}
