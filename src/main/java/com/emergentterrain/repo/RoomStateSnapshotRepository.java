package com.emergentterrain.repo;

import com.emergentterrain.domain.RoomStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RoomStateSnapshotRepository extends JpaRepository<RoomStateSnapshot, UUID> {
}
