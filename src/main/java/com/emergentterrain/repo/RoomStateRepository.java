package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomStateRepository extends JpaRepository<RoomState, UUID> {
    Optional<RoomState> findByRoom(Room room);
}
