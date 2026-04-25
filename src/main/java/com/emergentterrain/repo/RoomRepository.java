package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    Optional<Room> findTopByNameOrderByCreatedAtAsc(String name);
    Optional<Room> findTopByOrderByCreatedAtAsc();
}
