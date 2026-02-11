package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.domain.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, UUID> {
    List<RoomParticipant> findByRoomOrderByJoinedAt(Room room);
    List<RoomParticipant> findByRoomAndActiveTrueOrderByJoinedAt(Room room);
    Optional<RoomParticipant> findByRoomAndProfile(Room room, Profile profile);
    long countByActiveTrue();
}
