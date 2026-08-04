package com.emergentterrain.repo;

import com.emergentterrain.research.University;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniversityRepository extends JpaRepository<University, UUID> {

    List<University> findByRoomId(UUID roomId);

    Optional<University> findFirstByRoomId(UUID roomId);
}
