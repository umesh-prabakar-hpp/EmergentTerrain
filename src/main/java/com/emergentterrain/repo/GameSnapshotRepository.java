package com.emergentterrain.repo;

import com.emergentterrain.domain.GameSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import com.emergentterrain.domain.Profile;

public interface GameSnapshotRepository extends JpaRepository<GameSnapshot, UUID> {
    Optional<GameSnapshot> findTopByProfileOrderByCreatedAtDesc(Profile profile);
    List<GameSnapshot> findTop10ByProfileOrderByCreatedAtDesc(Profile profile);
}
